package com.b2y.danmaku.hook

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import com.b2y.danmaku.core.Log
import com.b2y.danmaku.core.PlaybackClock
import com.b2y.danmaku.core.VideoSessionController
import com.b2y.danmaku.hook.fingerprint.PlayerFingerprintHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 播放时钟的全局持有者。所有 hook（媒体会话 / DEX 指纹 / 手动校准）都向这个时钟提交采样。
 */
object PlaybackClockHolder {
    val clock: PlaybackClock = PlaybackClock()
}

/**
 * 通过 Android 框架的 [MediaSession] 获取播放进度、倍速与元数据。
 *
 * 为什么可行：YouTube 客户端的播放服务一定会创建平台 `android.media.session.MediaSession`
 * （锁屏控制 / 通知栏 / Android Auto 都依赖它），而 `android.media.session.*` 是框架类，
 * 不会被 YouTube 的混淆改动，因此这是**版本无关**的同步方案。
 *
 * 位置精度：`PlaybackState.getPosition()` 配合 `getLastPositionUpdateTime()` 与
 * `getPlaybackSpeed()` 可以精确外推当前播放位置；YouTube 在播放/暂停/跳转/变速时都会更新它。
 */
object MediaSessionWatcher {

    @Volatile
    private var lastSessionId: String? = null

    fun install(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)

        XposedBridge.hookAllMethods(cls, "setPlaybackState", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val state = param.args.firstOrNull() as? PlaybackState ?: return
                    submit(state, param.thisObject as? MediaSession)
                } catch (t: Throwable) {
                    Log.w("解析 PlaybackState 失败", t)
                }
            }
        })

        XposedBridge.hookAllMethods(cls, "setMetadata", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val md = param.args.firstOrNull() as? MediaMetadata ?: return
                    val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    val durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    Log.d("媒体元数据: title=$title mediaId=$mediaId artist=$artist duration=$durationMs")
                    VideoSessionController.onMediaMetadata(title, mediaId, artist, durationMs)
                } catch (t: Throwable) {
                    Log.w("解析 MediaMetadata 失败", t)
                }
            }
        })

        XposedBridge.hookAllMethods(cls, "setActive", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val active = param.args.firstOrNull() as? Boolean ?: return
                Log.d("MediaSession active=$active")
            }
        })

        Log.i("MediaSessionWatcher 安装完成")
    }

    private fun submit(state: PlaybackState, session: MediaSession?) {
        // 只信任“正在播放”的会话位置；YouTube 可能同时存在多个会话（如 Chrome 投屏 / 音乐）
        val sessionId = try {
            session?.sessionToken?.toString()
        } catch (_: Throwable) {
            null
        }
        lastSessionId = sessionId

        val position = state.position.coerceAtLeast(0L)
        val speed = state.playbackSpeed.let { if (it <= 0.01f) 1.0f else it }
        val playing = when (state.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING -> true
            else -> false
        }
        val updateTime = state.lastPositionUpdateTime.let {
            if (it <= 0L) SystemClock.elapsedRealtime() else it
        }
        Log.d("播放状态: state=${state.state} pos=$position speed=$speed playing=$playing")
        if (PlayerFingerprintHook.videoTimeHookActive) {
            // 位置已经由 YouTube 内部的时间指纹提供（更精确），媒体会话只负责播放状态与倍速。
            // 不能把 updateTime（过去的时间戳）传进去当锚点，否则时钟会被拽回过去。
            PlaybackClockHolder.clock.submitFlags(speed, playing)
        } else {
            PlaybackClockHolder.clock.submit(position, speed, playing, updateTime)
        }
    }
}
