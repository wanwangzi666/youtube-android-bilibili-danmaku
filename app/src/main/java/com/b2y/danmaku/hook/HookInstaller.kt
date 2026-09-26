package com.b2y.danmaku.hook

import com.b2y.danmaku.core.Log
import com.b2y.danmaku.hook.fingerprint.PlayerFingerprintHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 统一的 Hook 安装入口。
 *
 * 设计原则：**尽量只 hook Android 框架类**（Activity / MediaSession / SurfaceView ...），
 * 这些类不会被 YouTube 的混淆影响，因此模块在 YouTube 版本升级后依然可用。
 * 只有在必须获取 YouTube 内部数据（视频 ID）时才使用 [fingerprint] 包里的 DEX 指纹扫描。
 */
object HookInstaller {

    @Volatile
    var installed: Boolean = false
        private set

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lpparam.classLoader

        safe("ActivityWatcher") { ActivityWatcher.install(cl) }
        safe("MediaSessionWatcher") { MediaSessionWatcher.install(cl) }
        safe("ShortsDetector") { ShortsDetector.install(cl) }
        safe("VideoSurfaceTracker") { VideoSurfaceTracker.install(cl) }
        safe("PlayerFingerprintHook") { PlayerFingerprintHook.install(cl, lpparam.appInfo?.sourceDir, lpparam.appInfo?.splitSourceDirs) }

        Log.i("Hook 安装完成")
    }

    private inline fun safe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("安装 $name 失败", t)
        }
    }
}
