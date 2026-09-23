package com.b2y.danmaku.core

import android.os.SystemClock

/**
 * 播放时钟。
 *
 * YouTube 客户端内部播放器的位置无法直接读取（代码被混淆），因此这里以“锚点 + 外推”的方式工作：
 * 每当拿到一次可信的位置采样（媒体会话 PlaybackState、指纹 hook 的视频时间等），就记录
 * 锚点；播放中时按 playbackSpeed 外推到当前时刻。
 *
 * 该类不依赖任何 Android API 之外的东西（只有 [SystemClock]），便于单元测试。
 */
class PlaybackClock(
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {

    data class Sample(
        val positionMs: Long,
        val speed: Float,
        val playing: Boolean,
        val elapsedRealtimeMs: Long
    )

    interface Listener {
        /** 发生了跳转（拖动进度条 / 自动跳到下一段） */
        fun onSeek(fromMs: Long, toMs: Long) = Unit

        fun onPlayStateChanged(playing: Boolean) = Unit

        fun onSpeedChanged(speed: Float) = Unit
    }

    /** 位置跳变超过该阈值即认为用户拖动/跳转 */
    var seekThresholdMs: Long = 1200

    /**
     * 允许的"轻微回退"容差。
     *
     * YouTube 上报的播放位置存在量化/抖动（帧对齐、音视频时钟差异），经常比我们外推出来的位置
     * 小几十毫秒。如果每次采样都重新锚定，播放位置就会呈锯齿状来回抖动，进而被上层误判为
     * 跳转并触发弹幕全量重排（表现为弹幕上下乱闪）。因此回退幅度在容差内时不重锚。
     */
    var driftToleranceMs: Long = 250

    /** 小于该值的位置变化不认为发生变化（避免抖动） */
    var noiseMs: Long = 60

    private var anchorPositionMs: Long = 0L
    private var anchorElapsedMs: Long = 0L
    private var playing: Boolean = false
    private var speed: Float = 1.0f
    private var valid: Boolean = false
    private val listeners = ArrayList<Listener>()

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun isValid(): Boolean = valid

    val isPlaying: Boolean get() = playing

    val playbackSpeed: Float get() = speed

    /** 当前视频时间（毫秒），未播放时返回锚点位置 */
    fun positionMs(): Long {
        if (!valid) return 0L
        if (!playing) return anchorPositionMs
        val elapsed = elapsedRealtimeProvider() - anchorElapsedMs
        val delta = (elapsed * speed).toLong()
        val p = anchorPositionMs + delta
        return if (p < 0L) 0L else p
    }

    fun positionMsAt(elapsedRealtimeMs: Long): Long {
        if (!valid) return 0L
        if (!playing) return anchorPositionMs
        val delta = ((elapsedRealtimeMs - anchorElapsedMs) * speed).toLong()
        val p = anchorPositionMs + delta
        return if (p < 0L) 0L else p
    }

    fun reset() {
        valid = false
        anchorPositionMs = 0
        anchorElapsedMs = 0
        playing = false
        speed = 1.0f
    }

    /**
     * 提交一次位置采样。会自动识别 播放/暂停、倍速、跳转 并回调监听者。
     *
     * 关键行为：
     * - 播放中且回退幅度 <= [driftToleranceMs] 时**不重新锚定**，避免位置锯齿；
     * - 播放中且偏差 > [seekThresholdMs] 时判定为跳转，回调 `onSeek`；
     * - 暂停时始终以采样值为准（位置需要精确冻结）。
     */
    fun submit(positionMs: Long, speed: Float, playing: Boolean) {
        submit(positionMs, speed, playing, elapsedRealtimeProvider())
    }

    fun submit(positionMs: Long, speed: Float, playing: Boolean, elapsedRealtimeMs: Long) {
        val pos = if (positionMs < 0L) 0L else positionMs
        val safeSpeed = if (speed <= 0.01f) 1.0f else speed

        if (!valid) {
            anchorPositionMs = pos
            anchorElapsedMs = elapsedRealtimeMs
            this.playing = playing
            this.speed = safeSpeed
            valid = true
            return
        }

        val expected = positionMsAt(elapsedRealtimeMs)
        val delta = pos - expected
        val playChanged = playing != this.playing
        val speedChanged = kotlin.math.abs(safeSpeed - this.speed) > 0.001f

        // 真正的拖动 / 跳转：位置发生大幅跳变
        if (this.playing && playing && kotlin.math.abs(delta) > seekThresholdMs) {
            anchorPositionMs = pos
            anchorElapsedMs = elapsedRealtimeMs
            this.playing = playing
            this.speed = safeSpeed
            notifySeek(expected, pos)
            return
        }

        // 何时重新锚定：
        //  - 播放状态变化（尤其是暂停 -> 恢复，必须以当前时刻重新锚定，否则暂停的那段时间会被算成播放）
        //  - 暂停中（位置需要精确冻结）
        //  - 采样值 >= 外推值：正常向前修正
        //  - 回退幅度超过容差：真实的位置滞后（缓冲/时钟源切换）
        // 唯独"播放中、小幅回退"不重锚，保证 positionMs() 单调，避免上层误判为跳转。
        val shouldReanchor = playChanged || !playing || delta >= 0L || delta < -driftToleranceMs
        if (shouldReanchor) {
            anchorPositionMs = pos
            anchorElapsedMs = elapsedRealtimeMs
        }
        this.playing = playing
        this.speed = safeSpeed

        if (playChanged) notifyPlayState(playing)
        if (speedChanged) notifySpeed(safeSpeed)
    }

    /**
     * 只更新倍速 / 播放暂停状态，不覆盖位置锚点。
     *
     * 用于「位置由指纹 hook 提供、播放状态由媒体会话提供」的场景：
     * 以**当前时刻**外推出的位置作为新锚点，因此暂停时位置精确冻结在当前值，
     * 恢复播放时继续从该点外推。
     *
     * 注意：这里刻意不接受"状态更新时间"作为参数——`PlaybackState.lastPositionUpdateTime`
     * 是过去的时间戳，用它当锚点会把时钟拽回过去。
     */
    fun submitFlags(speed: Float, playing: Boolean) {
        val now = elapsedRealtimeProvider()
        val pos = positionMsAt(now)
        submit(pos, speed, playing, now)
    }

    /** 主动告知“发生了跳转”（例如指纹 hook 捕获到 seek 调用） */
    fun notifySeekDetected(fromMs: Long, toMs: Long, elapsedRealtimeMs: Long = elapsedRealtimeProvider()) {
        anchorPositionMs = toMs
        anchorElapsedMs = elapsedRealtimeMs
        valid = true
        notifySeek(fromMs, toMs)
    }

    private fun notifySeek(fromMs: Long, toMs: Long) {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) {
            try {
                l.onSeek(fromMs, toMs)
            } catch (t: Throwable) {
                Log.w("PlaybackClock listener onSeek 异常", t)
            }
        }
    }

    private fun notifyPlayState(playing: Boolean) {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) {
            try {
                l.onPlayStateChanged(playing)
            } catch (t: Throwable) {
                Log.w("PlaybackClock listener onPlayStateChanged 异常", t)
            }
        }
    }

    private fun notifySpeed(speed: Float) {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) {
            try {
                l.onSpeedChanged(speed)
            } catch (t: Throwable) {
                Log.w("PlaybackClock listener onSpeedChanged 异常", t)
            }
        }
    }
}
