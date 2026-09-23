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
    }.toString()

    fun decode(json: String): DanmakuSettings {
        val o = JSONObject(json)
        val d = DanmakuSettings()
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
            preferOembedTitle = o.optBoolean("preferOembedTitle", d.preferOembedTitle)
        )
    }
}
