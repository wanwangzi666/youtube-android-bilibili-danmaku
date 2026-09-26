package com.b2y.danmaku.core

import org.json.JSONObject

/**
 * 设置的 JSON 编解码。使用手工序列化，避免依赖反射/注解，方便 JVM 单元测试。
 */
object SettingsCodec {

    fun encode(s: DanmakuSettings): String = JSONObject().apply {
        put("enabled", s.enabled)
        put("opacity", s.opacity)
        put("fontSizeSp", s.fontSizeSp.toDouble())
        put("speed", s.speed.toDouble())
        put("trackSpacingDp", s.trackSpacingDp)
        put("displayAreaPercent", s.displayAreaPercent)
        put("timeOffsetMs", s.timeOffsetMs)
        put("weightThreshold", s.weightThreshold)
        put("maxTracks", s.maxTracks)
        put("matchThreshold", s.matchThreshold)
        put("multiMatchMode", s.multiMatchMode)
        put("autoLoad", s.autoLoad)
        put("showFloatButton", s.showFloatButton)
        put("maxDanmakuCount", s.maxDanmakuCount)
        put("showBottom", s.showBottom)
        put("showTop", s.showTop)
        put("sessData", s.sessData)
        put("manualBvid", s.manualBvid)
        put("preferOembedTitle", s.preferOembedTitle)
        put("matchInShorts", s.matchInShorts)
    }.toString()

    /**
     * [DanmakuSettings.matchInShorts] 在配置里缺失时使用的默认值。
     *
     * **刻意定为 `false`（Shorts 里不匹配弹幕）**，而数据类里的默认值是 `true`：
     * - 全新安装：没有配置文件 → 走这里 → 默认**不匹配** Shorts（刷短视频本来就不该有弹幕）
     * - 从 1.0.0 / 1.1.0 升级：老配置里没有这个键 → 也走这里 → **自动迁移**成不匹配，
     *   用户不用再去设置页里手动取消勾选
     * - 用户**显式**设置过（键存在）→ 永远以用户的选择为准
     */
    const val DEFAULT_MATCH_IN_SHORTS = false

    /**
     * 「Shorts 里也匹配」的最终生效值。
     *
     * [runtimeOverride] 是**被注入进程自己维护的运行时开关**（播放页面板里的全局开关写的），
     * 它优先于 [fromSettings]（模块 App 保存的 JSON 配置）。
     *
     * 为什么这样定优先级：用户实测反馈过，模块 App 里取消了勾选、YouTube 进程里通过
     * `XSharedPreferences` 读到的却一直是旧值（诊断信息显示「设置=允许匹配」）。
     * 运行时开关走的是被注入进程自己的 SharedPreferences 文件，不依赖跨进程机制，
     * 因此一定能同步；把它的优先级放高，面板开关就必定生效。
     */
    fun resolveMatchInShorts(runtimeOverride: Boolean?, fromSettings: Boolean): Boolean =
        runtimeOverride ?: fromSettings

    fun decode(json: String): DanmakuSettings {
        val o = JSONObject(json)
        val d = DanmakuSettings()
        val defaultMatchInShorts =
            if (o.has("matchInShorts")) d.matchInShorts else DEFAULT_MATCH_IN_SHORTS
        return DanmakuSettings(
            enabled = o.optBoolean("enabled", d.enabled),
            opacity = o.optInt("opacity", d.opacity),
            fontSizeSp = o.optDouble("fontSizeSp", d.fontSizeSp.toDouble()).toFloat(),
            speed = o.optDouble("speed", d.speed.toDouble()).toFloat(),
            trackSpacingDp = o.optInt("trackSpacingDp", d.trackSpacingDp),
            displayAreaPercent = o.optInt("displayAreaPercent", d.displayAreaPercent),
            timeOffsetMs = o.optInt("timeOffsetMs", d.timeOffsetMs),
            weightThreshold = o.optInt("weightThreshold", d.weightThreshold),
            maxTracks = o.optInt("maxTracks", d.maxTracks),
            matchThreshold = o.optInt("matchThreshold", d.matchThreshold),
            multiMatchMode = o.optString("multiMatchMode", d.multiMatchMode),
            autoLoad = o.optBoolean("autoLoad", d.autoLoad),
            showFloatButton = o.optBoolean("showFloatButton", d.showFloatButton),
            maxDanmakuCount = o.optInt("maxDanmakuCount", d.maxDanmakuCount),
            showBottom = o.optBoolean("showBottom", d.showBottom),
            showTop = o.optBoolean("showTop", d.showTop),
            sessData = o.optString("sessData", d.sessData),
            manualBvid = o.optString("manualBvid", d.manualBvid),
            preferOembedTitle = o.optBoolean("preferOembedTitle", d.preferOembedTitle),
            matchInShorts = o.optBoolean("matchInShorts", defaultMatchInShorts)
        )
    }
}
