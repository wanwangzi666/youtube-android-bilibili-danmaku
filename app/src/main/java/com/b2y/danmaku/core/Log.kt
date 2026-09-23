package com.b2y.danmaku.core

import android.util.Log as AndroidLog
import de.robv.android.xposed.XposedBridge

/**
 * 统一日志。模块运行在 YouTube 进程中，日志会输出到 Xposed 日志（Vector 管理器内可查看）。
 */
object Log {
    const val TAG = "B2Y"

    @Volatile
    var verbose: Boolean = false

    @Volatile
    private var xposedOk: Boolean = true

    private fun write(level: Char, msg: String, t: Throwable? = null) {
        val line = if (t == null) msg else "$msg\n${AndroidLog.getStackTraceString(t)}"
        if (xposedOk) {
            try {
                XposedBridge.log("[$TAG] $level $line")
                return
            } catch (_: Throwable) {
                xposedOk = false
            }
        }
        try {
            when (level) {
                'E' -> AndroidLog.e(TAG, line)
                'W' -> AndroidLog.w(TAG, line)
                'D' -> AndroidLog.d(TAG, line)
                else -> AndroidLog.i(TAG, line)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun d(msg: String) {
        if (verbose) write('D', msg)
    }

    fun i(msg: String) = write('I', msg)

    fun w(msg: String, t: Throwable? = null) = write('W', msg, t)

    fun e(msg: String, t: Throwable? = null) = write('E', msg, t)
}
