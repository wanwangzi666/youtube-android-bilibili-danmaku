package com.b2y.danmaku.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackClock] 的单元测试。
 *
 * 重点回归：
 * 1. 位置采样抖动（YouTube 上报的时间偶尔比外推值小几十毫秒）**不得**让时钟回退，
 *    否则上层会把每帧的微小回退当成"跳转"，触发弹幕全量重排 —— 表现为弹幕上下乱闪；
 * 2. `submitFlags` 不得使用"状态更新时间"（过去的时间戳）当锚点，否则暂停瞬间位置会跳回过去。
 */
class PlaybackClockTest {

    private class FakeTime(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private class SeekRecorder : PlaybackClock.Listener {
        val seeks = ArrayList<Pair<Long, Long>>()
        val playStates = ArrayList<Boolean>()
        val speeds = ArrayList<Float>()

        override fun onSeek(fromMs: Long, toMs: Long) {
            seeks.add(fromMs to toMs)
        }

        override fun onPlayStateChanged(playing: Boolean) {
            playStates.add(playing)
        }

        override fun onSpeedChanged(speed: Float) {
            speeds.add(speed)
        }
    }

    @Test
    fun `播放中位置单调不减（采样抖动不回退）`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        clock.submit(10_000L, 1f, true)
        assertEquals(10_000L, clock.positionMs())

        t.advance(100)
        assertEquals(10_100L, clock.positionMs())

        // 采样抖动：上报值比外推值小 50ms
        clock.submit(10_050L, 1f, true)
        assertEquals("抖动不应让位置回退", 10_100L, clock.positionMs())

        t.advance(100)
        assertEquals("锚点保持不变，继续外推", 10_200L, clock.positionMs())

        // 连续抖动也不能累积出"锯齿"
        var last = clock.positionMs()
        repeat(30) {
            t.advance(16)
            clock.submit(clock.positionMs() - 30L, 1f, true)
            val now = clock.positionMs()
            assertTrue("位置出现回退: $last -> $now", now >= last)
            last = now
        }
    }

    @Test
    fun `轻微回退不触发 seek 回调`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        val rec = SeekRecorder()
        clock.addListener(rec)

        clock.submit(10_000L, 1f, true)
        t.advance(500)
        repeat(20) {
            clock.submit(clock.positionMs() - 100L, 1f, true)
        }
        assertTrue("轻微抖动不应被判定为跳转", rec.seeks.isEmpty())
    }

    @Test
    fun `大幅跳变触发一次 seek 回调`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        val rec = SeekRecorder()
        clock.addListener(rec)

        clock.submit(10_000L, 1f, true)
        t.advance(100)
        clock.submit(60_000L, 1f, true)

        assertEquals(1, rec.seeks.size)
        val (from, to) = rec.seeks[0]
        assertEquals(10_100L, from)
        assertEquals(60_000L, to)
        assertEquals("跳转后位置应立刻生效", 60_000L, clock.positionMs())
    }

    @Test
    fun `向后拖动同样触发 seek 回调`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        val rec = SeekRecorder()
        clock.addListener(rec)

        clock.submit(120_000L, 1f, true)
        t.advance(100)
        clock.submit(30_000L, 1f, true)

        assertEquals(1, rec.seeks.size)
        assertEquals(30_000L, clock.positionMs())
    }

    @Test
    fun `submitFlags 暂停时冻结在当前时刻的位置`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        clock.submit(10_000L, 1f, true)

        t.advance(1_000)
        clock.submitFlags(1f, false)
        assertEquals("暂停应冻结在外推到的当前位置", 11_000L, clock.positionMs())

        t.advance(5_000)
        assertEquals("暂停后位置不再推进", 11_000L, clock.positionMs())
        assertFalse(clock.isPlaying)
    }

    @Test
    fun `submitFlags 恢复播放后从冻结位置继续`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        clock.submit(10_000L, 1f, true)
        t.advance(1_000)
        clock.submitFlags(1f, false)
        t.advance(5_000)
        clock.submitFlags(1f, true) // 从 11_000ms 处恢复
        t.advance(1_000)
        assertEquals(12_000L, clock.positionMs())
    }

    @Test
    fun `submitFlags 记录倍速与播放状态变化`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        val rec = SeekRecorder()
        clock.addListener(rec)

        clock.submit(0L, 1f, true)
        clock.submitFlags(2.0f, true)
        t.advance(1_000)
        assertEquals("2 倍速下 1 秒真实时间推进 2 秒", 2_000L, clock.positionMs())
        assertTrue(rec.speeds.contains(2.0f))
    }

    @Test
    fun `倍速播放位置按倍率推进`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        clock.submit(0L, 1.5f, true)
        t.advance(1_000)
        assertEquals(1_500L, clock.positionMs())
    }

    @Test
    fun `reset 后回到未初始化状态`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        clock.submit(5_000L, 1f, true)
        clock.reset()
        assertFalse(clock.isValid())
        assertEquals(0L, clock.positionMs())
    }

    @Test
    fun `零或负的倍速回退为 1 倍速`() {
        val t = FakeTime()
        val clock = PlaybackClock { t.now }
        clock.submit(0L, 0f, true)
        assertEquals(1f, clock.playbackSpeed)
        t.advance(1_000)
        assertEquals(1_000L, clock.positionMs())
    }
}
