package com.b2y.danmaku

import com.b2y.danmaku.core.Log
import com.b2y.danmaku.hook.HookInstaller
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。Vector / LSPosed / 经典 Xposed 均通过 `assets/xposed_init` 找到本类。
 */
class B2YModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        Log.i("B2Y 注入 YouTube 进程: ${lpparam.processName} (v${BuildConfig.VERSION_NAME})")
        try {
            HookInstaller.install(lpparam)
        } catch (t: Throwable) {
            Log.e("Hook 安装失败", t)
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.google.android.youtube"
    }
}
