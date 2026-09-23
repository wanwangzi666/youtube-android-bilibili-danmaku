package com.b2y.danmaku.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.b2y.danmaku.core.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 通过 hook 框架类的构造函数收集视频渲染用的 Surface。
 *
 * YouTube 的播放器最终会把画面渲染到 `SurfaceView` / `TextureView` 上；这两个都是框架类，
 * 不受混淆影响。收集到之后取“面积最大且可见”的一个作为弹幕显示区域。
 */
object VideoSurfaceTracker {

    private val refs: MutableList<WeakReference<View>> =
        Collections.synchronizedList(ArrayList<WeakReference<View>>())

    private val hookedClasses = mutableSetOf<String>()

    /** 最近一次选中的画面来源描述（诊断用） */
    @Volatile
    var lastChosenDescription: String = "未找到 SurfaceView / TextureView"
        private set

    /** 最近一次找到画面的时刻（elapsedRealtime），用于判断"当前是否在播放页" */
    @Volatile
    var lastFoundRealtimeMs: Long = 0L
        private set

    /** 最近一次找到的画面占屏幕面积的比例，用于区分"正片"与首页小窗预览 */
    @Volatile
    var lastChosenAreaRatio: Float = 0f
        private set

    fun install(classLoader: ClassLoader) {
        hookConstructors("android.view.SurfaceView", classLoader)
        hookConstructors("android.view.TextureView", classLoader)
        Log.i("VideoSurfaceTracker 安装完成")
    }

    fun register(view: View) {
        prune()
        synchronized(refs) {
            if (refs.size > 64) refs.removeAt(0)
            refs.add(WeakReference(view))
        }
    }

    private fun hookConstructors(className: String, classLoader: ClassLoader) {
        if (!hookedClasses.add(className)) return
        val cls = try {
            XposedHelpers.findClass(className, classLoader)
        } catch (t: Throwable) {
            Log.w("找不到 $className", t)
            return
        }
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                register(view)
            }
        })
    }

    private fun prune() {
        synchronized(refs) {
            val it = refs.iterator()
            while (it.hasNext()) {
                val v = it.next().get()
                if (v == null || !v.isAttachedToWindow) it.remove()
            }
        }
    }

    /**
     * 返回当前视频画面在屏幕坐标系中的矩形；找不到返回 null。
     */
    fun findVideoRectOnScreen(activity: Activity): Rect? {
        prune()
        val snapshot = synchronized(refs) { refs.mapNotNull { it.get() } }
        if (snapshot.isEmpty()) return null

        val screen = Rect()
        activity.window?.decorView?.getGlobalVisibleRect(screen)
        val screenArea = if (screen.width() > 0 && screen.height() > 0) {
            screen.width().toLong() * screen.height()
        } else {
            val dm = activity.resources.displayMetrics
            dm.widthPixels.toLong() * dm.heightPixels
        }

        var best: View? = null
        var bestArea = 0L
        val tmp = Rect()
        val decor = activity.window?.decorView
        for (v in snapshot) {
            if (!v.isShown || v.width <= 0 || v.height <= 0) continue
            if (!v.getGlobalVisibleRect(tmp)) continue
            // 必须属于当前 Activity 的视图树（YouTube 可能持有其他窗口/悬浮窗的 Surface）
            if (decor != null && !isDescendantOf(v, decor)) continue
            val area = tmp.width().toLong() * tmp.height()
            // 过滤掉过小的 Surface（图标、缩略图等）
            if (area < screenArea / 12) continue
            if (area > bestArea) {
                bestArea = area
                best = v
            }
        }
        val chosen = best ?: run {
            lastChosenDescription = "未找到可见画面（已注册 ${snapshot.size} 个候选）"
            lastChosenAreaRatio = 0f
            return null
        }
        val rect = Rect()
        if (!chosen.getGlobalVisibleRect(rect)) {
            lastChosenDescription = "候选不可见"
            lastChosenAreaRatio = 0f
            return null
        }
        val area = rect.width().toLong() * rect.height()
        lastChosenAreaRatio = if (screenArea > 0) area.toFloat() / screenArea.toFloat() else 0f
        lastFoundRealtimeMs = android.os.SystemClock.elapsedRealtime()
        lastChosenDescription =
            "${chosen.javaClass.name} ${rect.width()}×${rect.height()}@(${rect.left},${rect.top}) " +
                "占比 ${(lastChosenAreaRatio * 100).toInt()}%"
        Log.d("视频画面区域(屏幕坐标): $rect (${chosen.javaClass.name})")
        return rect
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var cur: View? = view.parent as? View
        var guard = 0
        while (cur != null && guard++ < 64) {
            if (cur === ancestor) return true
            cur = cur.parent as? View
        }
        return false
    }
}
