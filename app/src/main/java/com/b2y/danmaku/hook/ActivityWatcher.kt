package com.b2y.danmaku.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.b2y.danmaku.core.Log
import com.b2y.danmaku.core.VideoSessionController
import com.b2y.danmaku.ui.DanmakuOverlay
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 通过 hook `android.app.Application#onCreate` 拿到 Application，注册 Activity 生命周期回调，
 * 从而在 YouTube 的 Activity 上挂载弹幕浮层。全程只涉及框架类，不受混淆影响。
 */
object ActivityWatcher {

    private val overlays = HashMap<Activity, DanmakuOverlay>()

    @Volatile
    private var application: Application? = null

    /** 最近一次 onActivityResumed 的 Activity：判断「当前在哪个界面」时比 [overlays] 的 key 更准 */
    @Volatile
    private var resumedActivity: Activity? = null

    fun install(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    application = app
                    try {
                        app.registerActivityLifecycleCallbacks(Callbacks)
                        Log.i("已注册 Activity 生命周期回调")
                    } catch (t: Throwable) {
                        Log.e("注册 ActivityLifecycleCallbacks 失败", t)
                    }
                }
            }
        )
    }

    fun currentActivity(): Activity? = overlays.keys.firstOrNull { !it.isFinishing }

    /** 当前处于前台的 Activity（没有挂浮层的界面也能拿到） */
    fun foregroundActivity(): Activity? = resumedActivity?.takeIf { !it.isFinishing } ?: currentActivity()

    private object Callbacks : Application.ActivityLifecycleCallbacks {

        override fun onActivityResumed(activity: Activity) {
            try {
                if (activity.packageName != VideoSessionController.TARGET_PACKAGE &&
                    activity.packageName != "com.google.android.youtube"
                ) {
                    return
                }
                resumedActivity = activity
                val overlay = overlays.getOrPut(activity) {
                    DanmakuOverlay(activity).also {
                        it.attach()
                        Log.i("已为 ${activity.javaClass.name} 挂载弹幕浮层")
                    }
                }
                overlay.onResume()
                VideoSessionController.onActivityResumed(activity, overlay)
            } catch (t: Throwable) {
                Log.e("onActivityResumed 处理失败", t)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            try {
                overlays[activity]?.onPause()
            } catch (t: Throwable) {
                Log.w("onActivityPaused 处理失败", t)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            try {
                val overlay = overlays.remove(activity) ?: return
                overlay.detach()
                if (overlays.isEmpty()) VideoSessionController.onOverlayDetached()
            } catch (t: Throwable) {
                Log.w("onActivityDestroyed 处理失败", t)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
