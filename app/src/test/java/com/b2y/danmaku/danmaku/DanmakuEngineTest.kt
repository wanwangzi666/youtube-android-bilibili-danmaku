package com.b2y.danmaku.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [DanmakuEngine] 的单元测试（纯 JVM，不需要 Android 运行时）。
 *
 * 假测量器：[TextMeasurer] 返回 `text.length * fontSizePx * 0.6f`。
 */
class DanmakuEngineTest {

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private val measurer = TextMeasurer { text, fontSizePx -> text.length * fontSizePx * 0.6f }

    private fun engine(config: EngineConfig = EngineConfig()) = DanmakuEngine(measurer).also {
        it.setConfig(config)
    }

    private fun item(
        timeMs: Long,
        text: String = "default",
        mode: Int = DanmakuItem.MODE_SCROLL,
        weight: Int = DanmakuItem.DEFAULT_WEIGHT,
        color: Int = 0xFFFFFF
    ) = DanmakuItem(timeMs = timeMs, text = text, color = color, mode = mode, weight = weight)

    private fun List<ActiveDanmaku>.withText(text: String): ActiveDanmaku? =
        firstOrNull { it.text == text }

    // ------------------------------------------------------------------
    // 1. 基础发射
    // ------------------------------------------------------------------

    @Test
    fun loadThenUpdateAtItemTimeEmitsItem() {
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10))
        e.load(listOf(item(1000L, "A"), item(5000L, "B")))

        assertEquals(2, e.loadedCount)

        // 尚未到时间
        e.update(999L, 1080, 1920)
        assertEquals(0, e.activeCount)

        // 到时间当帧即发射
        e.update(1000L, 1080, 1920)
        assertEquals(1, e.activeCount)
        val a = e.activeItems.withText("A")
        assertNotNull(a)
        assertEquals(DanmakuItem.MODE_SCROLL, a!!.mode)
        assertEquals(0, a.track)
        assertEquals("A", e.activeItems[0].text)
        assertEquals(1000L, e.activeItems[0].timeMs)

        // 到达第二条（第一条 8s 后才到期，仍在场）
        e.update(5000L, 1080, 1920)
        assertEquals(2, e.activeCount)
        assertNotNull(e.activeItems.withText("B"))
    }

    @Test
    fun trackCountFollowsStageHeight() {
        val e = engine(EngineConfig(fontSizePx = 42f, trackSpacingPx = 10))
        e.load(listOf(item(0L)))
        e.update(0L, 1080, 1920)
        // floor(1920 / 52) = 36
        assertEquals(36, e.trackCount)
        assertEquals(52, e.trackHeight)
    }

    // ------------------------------------------------------------------
    // 2. 位置与存活
    // ------------------------------------------------------------------

    @Test
    fun rollingItemXMovesLeftAndIsReleasedAfterDuration() {
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10))
        val text = "abcdefghij"
        e.load(listOf(item(0L, text)))
        val width = text.length * 40f * 0.6f // 240f

        e.update(0L, 1000, 1000)
        val a = e.activeItems.withText(text)
        assertNotNull(a)
        val x0 = a!!.x
        // 起始位置：右边缘贴着舞台
        assertNear(1000f, x0, 0.01f)
        assertEquals(0, a.track)

        e.update(800L, 1000, 1000)
        val x1 = e.activeItems.withText(text)!!.x
        assertTrue("x 应随时间单调左移: $x0 -> $x1", x1 < x0)
        // p = 0.1 → x = 1000 - 0.1 * 1240 = 876
        assertNear(876f, x1, 0.5f)

        e.update(4000L, 1000, 1000)
        val x2 = e.activeItems.withText(text)!!.x
        assertTrue("继续左移: $x1 -> $x2", x2 < x1)

        // 基准时长 8000ms，到点释放
        e.update(8000L, 1000, 1000)
        assertEquals(0, e.activeCount)
        assertNull("对象应被回收，不再出现在活动列表", e.activeItems.withText(text))
    }

    @Test
    fun speedTwoHalvesLifetime() {
        // 半程仍在
        val half = engine(EngineConfig(speed = 2.0f))
        half.load(listOf(item(0L, "A")))
        half.update(0L, 1000, 1000)
        half.update(2000L, 1000, 1000)
        assertEquals(1, half.activeCount)

        // 整程（8000 / 2 = 4000ms）已释放
        val full = engine(EngineConfig(speed = 2.0f))
        full.load(listOf(item(0L, "A")))
        full.update(0L, 1000, 1000)
        full.update(4000L, 1000, 1000)
        assertEquals(0, full.activeCount)

        // 对照：1.0 倍速在 4000ms 时仍然存活
        val normal = engine(EngineConfig(speed = 1.0f))
        normal.load(listOf(item(0L, "A")))
        normal.update(0L, 1000, 1000)
        normal.update(4000L, 1000, 1000)
        assertEquals(1, normal.activeCount)
    }

    @Test
    fun speedTwoMovesFasterAtSameTimestamp() {
        val slow = engine(EngineConfig(speed = 1.0f))
        slow.load(listOf(item(0L, "ABCDEFGHIJ")))
        slow.update(0L, 1000, 1000)
        slow.update(1000L, 1000, 1000)
        val xSlow = slow.activeItems[0].x

        val fast = engine(EngineConfig(speed = 2.0f))
        fast.load(listOf(item(0L, "ABCDEFGHIJ")))
        fast.update(0L, 1000, 1000)
        fast.update(1000L, 1000, 1000)
        val xFast = fast.activeItems[0].x

        assertTrue("倍速下同一时刻应更靠左: slow=$xSlow fast=$xFast", xFast < xSlow)
    }

    @Test
    fun sameTimeItemsDoNotShareTrack() {
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10))
        e.load(listOf(item(0L, "AAAA"), item(0L, "BBBB")))
        e.update(0L, 1000, 1000)

        assertEquals(2, e.activeCount)
        val ta = e.activeItems.withText("AAAA")!!.track
        val tb = e.activeItems.withText("BBBB")!!.track
        assertTrue("同一时刻的两条弹幕不能落在同一轨道: $ta / $tb", ta != tb)
    }

    @Test
    fun allTracksBusyFallsBackToFastestClearingTrack() {
        // 轨道高度 50 → 1000px 舞台共 20 条轨道
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10))
        val burst = (0 until 20).map { item(0L, "item$it") }
        e.load(burst + item(0L, "late"))
        // 单帧发射上限为 12 条，因此需要两帧才能把 21 条全部发出
        e.update(0L, 1000, 1000)
        e.update(1L, 1000, 1000)
        assertEquals(21, e.activeCount)
        // 超过轨道数时不崩溃，且轨道索引都在范围内
        for (a in e.activeItems) {
            assertTrue("轨道索引越界: ${a.track}", a.track in 0 until e.trackCount)
        }
    }

    // ------------------------------------------------------------------
    // 3. 过滤
    // ------------------------------------------------------------------

    @Test
    fun weightThresholdFiltersLowWeightItems() {
        val e = engine(EngineConfig(weightThreshold = 7))
        e.load(
            listOf(
                item(0L, "w3", weight = 3),
                item(0L, "w6", weight = 6),
                item(0L, "w7", weight = 7),
                item(0L, "w9", weight = 9)
            )
        )
        e.update(0L, 1000, 1000)

        assertEquals(2, e.activeCount)
        assertNotNull(e.activeItems.withText("w7"))
        assertNotNull(e.activeItems.withText("w9"))
        assertNull(e.activeItems.withText("w3"))
        assertNull(e.activeItems.withText("w6"))
    }

    @Test
    fun weightThresholdZeroKeepsEverything() {
        val e = engine(EngineConfig(weightThreshold = 0))
        e.load(listOf(item(0L, "w1", weight = 1), item(0L, "w9", weight = 9)))
        e.update(0L, 1000, 1000)
        assertEquals(2, e.activeCount)
    }

    // ------------------------------------------------------------------
    // 4. 顶部 / 底部固定弹幕
    // ------------------------------------------------------------------

    @Test
    fun showTopFalseFiltersModeFive() {
        val e = engine(EngineConfig(showTop = false))
        e.load(listOf(item(0L, "TOP", mode = DanmakuItem.MODE_TOP), item(0L, "SCROLL")))
        e.update(0L, 1000, 1000)

        assertEquals(1, e.activeCount)
        assertNull(e.activeItems.withText("TOP"))
        assertNotNull(e.activeItems.withText("SCROLL"))
    }

    @Test
    fun showBottomFalseFiltersModeFour() {
        val e = engine(EngineConfig(showBottom = false))
        e.load(listOf(item(0L, "BOTTOM", mode = DanmakuItem.MODE_BOTTOM), item(0L, "SCROLL")))
        e.update(0L, 1000, 1000)

        assertEquals(1, e.activeCount)
        assertNull(e.activeItems.withText("BOTTOM"))
        assertNotNull(e.activeItems.withText("SCROLL"))
    }

    @Test
    fun topAndBottomFixedItemsUseDedicatedTracksAndAreCentred() {
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10))
        e.load(
            listOf(
                item(0L, "T1", mode = DanmakuItem.MODE_TOP),
                item(0L, "T2", mode = DanmakuItem.MODE_TOP),
                item(0L, "B1", mode = DanmakuItem.MODE_BOTTOM)
            )
        )
        e.update(0L, 1000, 1000)

        assertEquals(3, e.activeCount)
        val t1 = e.activeItems.withText("T1")!!
        val t2 = e.activeItems.withText("T2")!!
        val b1 = e.activeItems.withText("B1")!!

        // 顶部自上而下填充
        assertEquals(0, t1.track)
        assertEquals(1, t2.track)
        // 底部自下而上填充
        assertEquals(e.trackCount - 1, b1.track)
        // 水平居中
        assertNear((1000f - t1.width) / 2f, t1.x, 0.01f)
        assertNear((1000f - b1.width) / 2f, b1.x, 0.01f)

        // 固定弹幕存活 FIXED_DURATION_MS
        e.update(3999L, 1000, 1000)
        assertEquals(3, e.activeCount)
        e.update(4001L, 1000, 1000)
        assertEquals(0, e.activeCount)
    }

    // ------------------------------------------------------------------
    // 5. seek
    // ------------------------------------------------------------------

    @Test
    fun onSeekClearsActiveAndDoesNotReplayPast() {
        val e = engine()
        e.load(listOf(item(1000L, "a"), item(2000L, "b"), item(3000L, "c"), item(4000L, "d")))

        // 先正常播放到 3000ms：a / b / c 在场
        e.update(3000L, 1000, 1000)
        assertEquals(3, e.activeCount)

        // 跳到 2100ms：清空画面，且 a / b（已过去）被标记为已发射，不会重播
        e.onSeek(2100L)
        assertEquals(0, e.activeCount)

        e.update(2100L, 1000, 1000)
        assertEquals("seek 后不应重播过去的弹幕", 0, e.activeCount)

        // 未来的 c / d 照常发射
        e.update(3000L, 1000, 1000)
        val texts = e.activeItems.map { it.text }
        assertFalse(texts.contains("a"))
        assertFalse(texts.contains("b"))
        assertTrue(texts.contains("c"))
    }

    @Test
    fun seekForwardMarksSkippedItemsAsEmitted() {
        val e = engine()
        e.load(listOf(item(1000L, "a"), item(2000L, "b"), item(3000L, "c"), item(9000L, "d")))

        e.onSeek(5000L)
        // a / b / c 都被标记为已发射
        assertEquals(3, e.emittedCount)
        assertEquals(0, e.activeCount)

        // 只剩 d 待发射
        assertTrue(e.hasPending)
        e.update(9000L, 1000, 1000)
        assertEquals(1, e.activeCount)
        assertNotNull(e.activeItems.withText("d"))
        assertFalse(e.hasPending)
    }

    @Test
    fun clearReleasesEverything() {
        val e = engine()
        e.load(listOf(item(0L, "a"), item(0L, "b")))
        e.update(0L, 1000, 1000)
        assertEquals(2, e.activeCount)

        e.clear()
        assertEquals(0, e.activeCount)
        assertEquals(0, e.loadedCount)

        e.update(1000L, 1000, 1000)
        assertEquals(0, e.activeCount)
    }

    // ------------------------------------------------------------------
    // 6. 显示区域百分比 / 最大轨道数
    // ------------------------------------------------------------------

    @Test
    fun displayAreaPercentHalvesTrackCount() {
        val cfg = EngineConfig(fontSizePx = 40f, trackSpacingPx = 10) // 轨道高 50

        val full = engine(cfg)
        full.load(listOf(item(0L, "A")))
        full.update(0L, 1000, 1000)
        assertEquals(20, full.trackCount)

        val half = engine(cfg.copy(displayAreaPercent = 50))
        half.load(listOf(item(0L, "A")))
        half.update(0L, 1000, 1000)
        assertEquals(10, half.trackCount)

        assertEquals(full.trackCount / 2, half.trackCount)
    }

    @Test
    fun maxTracksCapsTrackCount() {
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10, maxTracks = 3))
        e.load(listOf(item(0L, "A")))
        e.update(0L, 1000, 1000)
        assertEquals(3, e.trackCount)
    }

    @Test
    fun trackCountIsAtLeastOneEvenWhenStageIsTiny() {
        val e = engine(EngineConfig(fontSizePx = 40f, trackSpacingPx = 10))
        e.load(listOf(item(0L, "A")))
        e.update(0L, 1000, 10)
        assertEquals(1, e.trackCount)
        assertEquals(1, e.activeCount)
    }

    // ------------------------------------------------------------------
    // 7. 不透明度
    // ------------------------------------------------------------------

    @Test
    fun opacityPercentMapsToAlpha() {
        val e = engine(EngineConfig(opacityPercent = 50))
        e.load(listOf(item(0L, "A")))
        e.update(0L, 1000, 1000)
        assertEquals(128, e.activeItems[0].alpha)
        assertEquals(128, e.alpha)

        val full = engine(EngineConfig(opacityPercent = 100))
        full.load(listOf(item(0L, "A")))
        full.update(0L, 1000, 1000)
        assertEquals(255, full.activeItems[0].alpha)
    }

    // ------------------------------------------------------------------
    // 8. 健壮性
    // ------------------------------------------------------------------

    @Test
    fun updateWithZeroStageWidthDoesNotCrash() {
        val e = engine()
        e.load(listOf(item(0L, "A"), item(0L, "B")))

        e.update(0L, 0, 0)
        assertEquals(0, e.activeCount)
        assertFalse(e.hasPending) // 已发射但未绘制

        e.update(1000L, 0, 1000)
        assertEquals(0, e.activeCount)

        e.update(2000L, 1000, 0)
        assertEquals(0, e.activeCount)

        // 舞台恢复后不再重播（这些条目已经"发射过"了）
        e.update(3000L, 1000, 1000)
        assertEquals(0, e.activeCount)
    }

    @Test
    fun updateWithSameTimestampIsIdempotent() {
        val e = engine()
        e.load(listOf(item(0L, "A")))
        e.update(0L, 1000, 1000)
        val first = e.activeItems.map { it.x to it.y }
        assertEquals(1, e.activeCount)

        e.update(0L, 1000, 1000)
        e.update(0L, 1000, 1000)
        assertEquals(1, e.activeCount)
        assertEquals(first, e.activeItems.map { it.x to it.y })
    }

    @Test
    fun updateWithoutLoadIsSafe() {
        val e = engine()
        e.update(0L, 1000, 1000)
        e.update(1234L, 1000, 1000)
        assertEquals(0, e.activeCount)
        assertEquals(0, e.loadedCount)
        assertFalse(e.hasPending)
    }

    @Test
    fun emptyTextIsNotEmitted() {
        val e = engine()
        e.load(listOf(item(0L, ""), item(0L, "A")))
        e.update(0L, 1000, 1000)
        assertEquals(1, e.activeCount)
        assertEquals("A", e.activeItems[0].text)
    }

    @Test
    fun activeObjectsAreReusedAfterRelease() {
        val e = engine()
        e.load(listOf(item(0L, "A"), item(9000L, "B")))
        e.update(0L, 1000, 1000)
        val first = e.activeItems[0]

        e.update(8000L, 1000, 1000) // A 到期释放
        assertEquals(0, e.activeCount)
        e.update(9000L, 1000, 1000) // B 复用同一个对象
        assertEquals(1, e.activeCount)
        assertTrue("对象池应复用实例", first === e.activeItems[0])
        assertEquals("B", e.activeItems[0].text)
    }

    @Test
    fun emissionIsCappedPerFrame() {
        val e = engine()
        e.load((0L until 100L).map { item(it * 100L, "n$it") })

        // 一帧之内积压了 100 条到点弹幕（跳帧/卡顿后的典型场景），只允许发射 12 条
        e.update(10_000L, 1000, 2000)
        assertTrue("单帧发射量应被限制在 12 条以内，实际 ${e.activeCount}", e.activeCount in 0..12)

        // 这 12 条最早的在 10000ms 时都已超过 8s 存活期并被回收，不会留下一屏残留
        assertEquals(0, e.activeCount)

        // 暂停：不推进时间就不会有新的发射
        e.update(10_000L, 1000, 2000)
        assertEquals(0, e.activeCount)

        // 继续播放：下一帧仍然只发射剩下的、真正到点的那些
        e.update(10_100L, 1000, 2000)
        assertTrue(e.activeCount in 1..12)
    }

    @Test
    fun seekBurstIsCappedPerFrame() {
        val e = engine()
        e.load((0L until 100L).map { item(it * 100L, "n$it") })

        // 跳到 9500ms：0..9500 的 96 条被标记为已发射，剩余 4 条都是未来弹幕
        e.onSeek(9500L)
        assertEquals(0, e.activeCount)

        // 恢复播放后的第一帧同样受单帧上限保护
        e.update(9600L, 1000, 2000)
        assertTrue("seek 后单帧发射量应有上限，实际 ${e.activeCount}", e.activeCount <= 12)
    }

    // ------------------------------------------------------------------
    // 9. DanmakuItem 默认值
    // ------------------------------------------------------------------

    @Test
    fun danmakuItemDefaults() {
        val d = DanmakuItem(timeMs = 1L, text = "t", color = 0x00FF00, mode = 4)
        assertEquals(5, d.weight)
        assertEquals(0f, d.fontSizeSp, 0f)
        assertEquals(DanmakuItem.MODE_BOTTOM, d.mode)
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    private fun assertNear(expected: Float, actual: Float, delta: Float) {
        assertTrue(
            "期望 $expected ± $delta，实际 $actual（差值 ${abs(expected - actual)}）",
            abs(expected - actual) <= delta
        )
    }
}
