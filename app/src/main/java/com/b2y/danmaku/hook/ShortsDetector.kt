package com.b2y.danmaku.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import com.b2y.danmaku.core.Log
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 判断「当前画面是不是 Shorts（竖屏短视频流）」。
 *
 * 为什么要判断：Shorts 是**全屏循环**播放的竖屏视频，标题通常是外文短句，B 站几乎没有对应
 * 内容；此时按标题搜索只会得到 0~25% 的匹配度，弹出「选择要同步的 B 站视频」纯属打扰。
 *
 * 判断依据（任一命中即认为在 Shorts）：
 *
 * 1. **播放器视图**（首选）：Shorts 播放器会用到 `com.google.android.libraries.youtube.reel.internal.*`
 *    里的几个类。这些类是 R8 的 **-keep** 目标（类名在 YouTube 21.38.124 的 DEX 里完好保留，
 *    说明它们被字符串/资源引用而无法被重命名），因此可以直接按类名在自己的 classLoader 里解析，
 *    不需要再走 DEX 指纹。模块不主动 hook 它们（避免解析全部方法带来的开销），只是在判断时
 *    按需解析一次，再遍历当前 Activity 的视图树找实例。
 * 2. **竖屏铺满几何**（兜底）：视频画面是竖屏且纵向几乎铺满屏幕 —— 这一层完全不依赖 YouTube
 *    内部实现，只在第 1 层失效时兜底，具体阈值与盲区见 [MIN_SCREEN_HEIGHT_RATIO]。
 *
 * 判定结果带有 [DECAY_MS] 的保鲜期：短时间（退出播放器、视图瞬时回收）内不会因为一次探测失败
 * 就来回抖动。
 */
object ShortsDetector {

    /** YouTube 内 short 播放器相关的类（R8 keep 下来的名字，版本间相对稳定） */
    private val CANDIDATE_CLASSES = listOf(
        "com.google.android.libraries.youtube.reel.internal.player.ReelPlayerView",
        "com.google.android.libraries.youtube.reel.internal.fragment.ReelTouchCaptureView",
        "com.google.android.libraries.youtube.reel.internal.pager.ReelRecyclerView",
        "com.google.android.libraries.youtube.reel.internal.pager.ReelLinearLayoutManager"
    )

    /** 判定结果的保鲜时长（毫秒） */
    private const val DECAY_MS = 3000L

    /** 竖屏判定容忍度：宽/高 必须小于该值才算竖屏 */
    private const val PORTRAIT_MAX_ASPECT = 0.9f

    /**
     * 画面高度至少要占屏幕高的比例。
     *
     * 0.55 是权衡出来的：
     * - Shorts 的画面在常见机型上从状态栏下方铺到底部（1080×2340 上约 1080×1860 = 80%），
     *   普通 16:9 手机上更是接近铺满；
     * - 普通播放页里的竖屏视频被「适应宽度」缩放后只有约 46% 高（1080×2340 上约 1080×1080），
     *   横屏正片约 26% 高。
     *
     * 已知残留盲区：在超长屏（宽高比 ≥ 2.1，例如 20:9）上看 9:16 的 Shorts 时，
     * 画面「适应宽度」后高度刚好掉到阈值以下，可能漏判。这类机型上依赖第 1 层（播放器视图）
     * 判定；两层都没命中时用户可以手动关掉弹幕。详见 README「已知限制」。
     */
    private const val MIN_SCREEN_HEIGHT_RATIO = 0.55f

    /**
     * 画面宽度至少要占屏幕宽的比例。
     *
     * 0.5 是为了排除首页信息流里的小窗预览，同时容忍 Shorts 里「原始比例竖屏视频」两侧
     * 的留白（9:16 视频在 20:9 手机上约 62% 宽）。
     */
    private const val MIN_SCREEN_WIDTH_RATIO = 0.5f

    private val refs: MutableList<WeakReference<View>> =
        Collections.synchronizedList(ArrayList<WeakReference<View>>())

    private var classLoader: ClassLoader? = null

    /** 惰性解析出的 Shorts 相关 View 类 */
    private var viewClasses: List<Class<*>>? = null

    /** 最近一次「确实是 Shorts」的时刻（elapsedRealtime） */
    @Volatile
    private var lastShortsRealtimeMs: Long = 0L

    /** 几何兜底使用的最近一次视频画面 */
    @Volatile
    private var lastVideoBounds: Rect? = null

    /** 最近一次判定依据（诊断用） */
    @Volatile
    var lastReason: String = "尚未判断"
        private set

    // ------------------------------------------------------------------ 安装

    fun install(cl: ClassLoader) {
        classLoader = cl
        Log.i("ShortsDetector 安装完成（候选类 ${CANDIDATE_CLASSES.size} 个）")
    }

    /**
     * 由 [VideoSurfaceTracker] 在 hook 到 SurfaceView / TextureView 构造时调用。
     * 这里只登记引用，真正的 Shorts 判断按需进行，不额外 hook YouTube 内部类。
     */
    fun registerView(view: View) {
        val classes = resolveViewClasses()
        if (classes.isEmpty() || !classes.any { it.isInstance(view) }) return
        synchronized(refs) {
            if (refs.size > 32) refs.removeAt(0)
            refs.add(WeakReference(view))
        }
        lastShortsRealtimeMs = android.os.SystemClock.elapsedRealtime()
        lastReason = "Shorts 播放器视图：${view.javaClass.name}"
        Log.i("检测到 Shorts 播放器视图：${view.javaClass.name}")
    }

    /** 浮层每轮刷新时把当前视频画面喂进来，供几何兜底判断使用 */
    fun onVideoBounds(bounds: Rect?) {
        lastVideoBounds = bounds?.let { Rect(it) }
    }

    // ------------------------------------------------------------------ 判断

    /**
     * 当前是否在 Shorts 播放器里。
     *
     * 只应在**主线程**调用（会遍历视图树）。
     */
    fun isShortsActive(activity: Activity?): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()

        // 1) 视图层：Shorts 播放器视图仍在当前视图树里
        if (now - lastShortsRealtimeMs <= DECAY_MS && hasLiveShortsView(activity)) {
            lastShortsRealtimeMs = now
            lastReason = "Shorts 播放器视图在线"
            return true
        }

        // 2) 几何兜底：竖屏全屏画面
        if (looksLikeShortsGeometry(lastVideoBounds, screenBounds(activity))) {
            lastShortsRealtimeMs = now
            lastReason = "竖屏全屏画面（几何判定）"
            return true
        }

        if (now - lastShortsRealtimeMs > DECAY_MS) {
            lastReason = "非 Shorts"
        }
        return false
    }

    private fun hasLiveShortsView(activity: Activity?): Boolean {
        val act = activity ?: return false
        val decor = act.window?.decorView ?: return false
        val snapshot = synchronized(refs) {
            refs.removeAll { it.get()?.isAttachedToWindow != true }
            refs.mapNotNull { it.get() }
        }
        for (v in snapshot) {
            if (!v.isShown) continue
            if (v.getGlobalVisibleRect(Rect()) && isDescendantOf(v, decor)) return true
        }
        return false
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        if (view === ancestor) return true
        var cur: View? = view.parent as? View
        var guard = 0
        while (cur != null && guard++ < 64) {
            if (cur === ancestor) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun screenBounds(activity: Activity?): Rect {
        val act = activity ?: return Rect()
        val rect = Rect()
        act.window?.decorView?.getGlobalVisibleRect(rect)
        if (rect.width() > 0 && rect.height() > 0) return rect
        val dm = act.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    // ------------------------------------------------------------------ 类解析

    private fun resolveViewClasses(): List<Class<*>> {
        viewClasses?.let { return it }
        val cl = classLoader ?: return emptyList()
        val found = ArrayList<Class<*>>()
        for (name in CANDIDATE_CLASSES) {
            try {
                found.add(Class.forName(name, false, cl))
            } catch (_: Throwable) {
                // 该类在本版本里不存在：正常情况，继续试下一个
            }
        }
        val frozen = found.toList()
        viewClasses = frozen
        if (frozen.isEmpty()) {
            Log.i("本版本未找到 Shorts 播放器类，改用竖屏全屏几何判定")
        } else {
            Log.i("Shorts 播放器类命中 ${frozen.size} 个：" + frozen.joinToString { it.simpleName })
        }
        return frozen
    }

    // ------------------------------------------------------------------ 纯函数（可单元测试）

    /**
     * Shorts 播放器相关的候选类名（对外暴露，便于单测在真实 APK 的 DEX 上验证这些名字
     * 确实存在 —— 换句话说，确认这一层判定依据没有因为 YouTube 版本更新而失效）。
     */
    val candidateClassNames: List<String> get() = CANDIDATE_CLASSES

    /** 几何兜底：视频画面是否为「竖屏 + 几乎占满屏幕」 */
    fun looksLikeShortsGeometry(videoBounds: Rect?, screenBounds: Rect?): Boolean {
        if (videoBounds == null || screenBounds == null) return false
        return looksLikeShortsGeometry(
            videoWidth = videoBounds.width(),
            videoHeight = videoBounds.height(),
            screenWidth = screenBounds.width(),
            screenHeight = screenBounds.height()
        )
    }

    /**
     * 几何兜底的实际判定规则（纯整数入参）。
     *
     * 之所以不直接接收 [Rect]：单元测试运行在没有真实 Android 实现的 JVM 上，
     * `android.graphics.Rect.width()` 是返回 0 的桩，无法验证。把规则抽成纯函数后，
     * 这条兜底逻辑就能在 JVM 上被完整覆盖。
     *
     * 两个条件（都满足才算 Shorts）：
     * 1. **竖屏**：宽/高 < [PORTRAIT_MAX_ASPECT] —— 一步排除所有横屏正片；
     * 2. **铺满屏幕**：高 ≥ 屏幕高 × [MIN_SCREEN_HEIGHT_RATIO] 且宽 ≥ 屏幕宽 × [MIN_SCREEN_WIDTH_RATIO]
     *    —— 排除普通播放页里的竖屏视频（上下大片黑边）与信息流小窗预览。
     */
    fun looksLikeShortsGeometry(
        videoWidth: Int,
        videoHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (videoWidth <= 0 || videoHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return false
        if (videoWidth.toFloat() / videoHeight.toFloat() >= PORTRAIT_MAX_ASPECT) return false
        return videoHeight >= screenHeight * MIN_SCREEN_HEIGHT_RATIO &&
            videoWidth >= screenWidth * MIN_SCREEN_WIDTH_RATIO
    }
}
