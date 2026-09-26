package com.b2y.danmaku.core

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置编解码的测试，重点是 `matchInShorts` 的默认值 / 迁移行为。
 *
 * 背景：`matchInShorts` 是 1.1.0 新增的键。它的语义是「在 Shorts 里也匹配弹幕」，
 * 而刷短视频本来就不该有弹幕，所以**新装和升级都应该默认不匹配**；只有用户显式设置过
 * 才以用户的选择为准。
 *
 * 数据类里的默认值（`true`）和配置解码时的默认值（`false`）刻意不同，
 * 这里把这个"陷阱"钉住，避免以后被顺手改回一致。
 */
class SettingsCodecTest {

    @Test
    fun `编码后包含 matchInShorts 键`() {
        val json = SettingsCodec.encode(DanmakuSettings(matchInShorts = false))
        assertTrue("encode 必须写出 matchInShorts", JSONObject(json).has("matchInShorts"))
    }

    @Test
    fun `旧配置缺少 matchInShorts 时迁移为不匹配`() {
        // 模拟 1.0.0 / 1.1.0 早期用户保存的配置：完全没有 matchInShorts 这个键
        val legacy = JSONObject().apply {
            put("enabled", true)
            put("autoLoad", true)
            put("matchThreshold", 90)
            put("preferOembedTitle", true)
        }.toString()

        val decoded = SettingsCodec.decode(legacy)
        assertFalse("旧配置升级后应默认不匹配 Shorts", decoded.matchInShorts)
        // 其它字段不能被迁移逻辑影响
        assertTrue("其它字段应保持原值", decoded.enabled)
        assertTrue("其它字段应保持原值", decoded.autoLoad)
        assertTrue("其它字段应保持原值", decoded.preferOembedTitle)
    }

    @Test
    fun `用户显式开启过则尊重用户选择`() {
        val json = SettingsCodec.encode(DanmakuSettings(matchInShorts = true))
        assertTrue("显式保存的 true 必须被读回", SettingsCodec.decode(json).matchInShorts)
    }

    @Test
    fun `用户显式关闭过则保持关闭`() {
        val json = SettingsCodec.encode(DanmakuSettings(matchInShorts = false))
        assertFalse("显式保存的 false 必须被读回", SettingsCodec.decode(json).matchInShorts)
    }

    @Test
    fun `编解码可以完整往返`() {
        val original = DanmakuSettings(
            enabled = false,
            opacity = 60,
            fontSizeSp = 22f,
            speed = 1.7f,
            trackSpacingDp = 12,
            displayAreaPercent = 45,
            timeOffsetMs = -800,
            weightThreshold = 4,
            maxTracks = 9,
            matchThreshold = 72,
            multiMatchMode = "ask",
            autoLoad = false,
            showFloatButton = false,
            maxDanmakuCount = 1234,
            showBottom = false,
            showTop = false,
            sessData = "abc",
            manualBvid = "BV1xx411c7mD",
            preferOembedTitle = false,
            matchInShorts = true
        )
        val roundTrip = SettingsCodec.decode(SettingsCodec.encode(original))
        assertTrue("往返后应完全一致：$roundTrip", roundTrip == original)
    }

    @Test
    fun `数据类默认值与配置默认值不同是有意的`() {
        // 代码内构造（例如控制面板「全部重置」）默认允许
        assertTrue(DanmakuSettings().matchInShorts)
        // 但配置解码时新装用户默认不允许
        assertFalse(SettingsCodec.DEFAULT_MATCH_IN_SHORTS)
        assertFalse(SettingsCodec.decode("{}").matchInShorts)
    }

    // ------------------------------------------------------------------ 生效值优先级

    @Test
    fun `没有运行时开关时使用设置文件的值`() {
        assertTrue(SettingsCodec.resolveMatchInShorts(null, fromSettings = true))
        assertFalse(SettingsCodec.resolveMatchInShorts(null, fromSettings = false))
    }

    @Test
    fun `运行时开关优先于设置文件`() {
        // 用户实测的坑：设置文件里是 true（跨进程读到旧值），但面板开关已经关掉了
        assertFalse(SettingsCodec.resolveMatchInShorts(runtimeOverride = false, fromSettings = true))
        assertTrue(SettingsCodec.resolveMatchInShorts(runtimeOverride = true, fromSettings = false))
    }

    // ------------------------------------------------------------------ 总开关

    @Test
    fun `总开关同样遵循运行时优先`() {
        assertFalse(SettingsCodec.resolveEnabled(runtimeOverride = false, fromSettings = true))
        assertTrue(SettingsCodec.resolveEnabled(runtimeOverride = true, fromSettings = false))
        assertTrue(SettingsCodec.resolveEnabled(runtimeOverride = null, fromSettings = true))
        assertFalse(SettingsCodec.resolveEnabled(runtimeOverride = null, fromSettings = false))
    }

    @Test
    fun `总开关默认是开`() {
        assertTrue(DanmakuSettings().enabled)
        assertTrue(SettingsCodec.decode("{}").enabled)
    }

    @Test
    fun `关掉的总开关能往返`() {
        val off = DanmakuSettings(enabled = false)
        assertFalse(SettingsCodec.decode(SettingsCodec.encode(off)).enabled)
    }
}
