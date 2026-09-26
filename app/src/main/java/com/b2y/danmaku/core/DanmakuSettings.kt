package com.b2y.danmaku.core

import android.content.Context
import de.robv.android.xposed.XSharedPreferences

/**
 * 弹幕与匹配相关设置。
 *
 * 设置持久化在模块自身的 SharedPreferences 中；被注入的 YouTube 进程通过 [XSharedPreferences]
 * 读取（Vector / LSPosed 均支持该机制）。
 */
data class DanmakuSettings(
    /** 总开关 */
    val enabled: Boolean = true,
    /** 不透明度 0..100 */
    val opacity: Int = 100,
    /** 字号（sp） */
    val fontSizeSp: Float = 16f,
    /** 弹幕滚动速度倍率 0.4..2.5，越大越快 */
    val speed: Float = 1.0f,
    /** 轨道间距（dp） */
    val trackSpacingDp: Int = 6,
    /** 弹幕显示区域占视频高度的百分比 10..100 */
    val displayAreaPercent: Int = 100,
    /** 时间轴偏移（毫秒），正数表示弹幕提前出现 */
    val timeOffsetMs: Int = 0,
    /** 权重过滤阈值：低于该值的弹幕不显示，0 表示不过滤 */
    val weightThreshold: Int = 0,
    /** 单屏最大轨道数，0 = 自动 */
    val maxTracks: Int = 0,
    /** 标题匹配阈值（百分比） */
    val matchThreshold: Int = 90,
    /** 多结果时的策略：mostDanmaku（选弹幕最多）/ ask（弹窗选择） */
    val multiMatchMode: String = "mostDanmaku",
    /** 进入视频后自动搜索并加载弹幕 */
    val autoLoad: Boolean = true,
    /** 是否显示悬浮控制按钮 */
    val showFloatButton: Boolean = true,
    /** 单视频最多加载弹幕条数，防止低端机卡顿 */
    val maxDanmakuCount: Int = 8000,
    /** 是否过滤底部弹幕 */
    val showBottom: Boolean = true,
    /** 是否过滤顶部弹幕 */
    val showTop: Boolean = true,
    /** B 站 Cookie（SESSDATA），用于需要登录的接口 */
    val sessData: String = "",
    /** 手动指定的 B 站视频（bvid 或 av 号或完整链接），非空时优先使用 */
    val manualBvid: String = "",
    /** 是否信任 YouTube 的媒体会话元数据（部分版本标题为空） */
    val preferOembedTitle: Boolean = true,
    /**
     * 是否在 Shorts（竖屏短视频流）里也匹配并显示弹幕。
     *
     * 取消勾选后：进入 Shorts 不自动搜索、已加载的弹幕与悬浮「弹」按钮一起隐藏
     * （面板里的手动加载不受影响）。
     *
     * 注意这个字段的默认值（`true`）**只用于代码内构造**（例如控制面板的「全部重置」）。
     * 真正生效的默认值由 [SettingsCodec.decode] 决定，那里是 `false`：
     * 全新安装、以及从旧版本升级（旧配置里没有这个键）都会默认**不匹配** Shorts；
     * 用户显式设置过则以用户的选择为准。
     */
    val matchInShorts: Boolean = true
) {
    companion object {
        const val PREF_NAME = "b2y_settings"
    }
}

/**
 * 设置读写。
 *
 * - 模块 App 进程：直接读写自己的 SharedPreferences。
 * - 被注入的 YouTube 进程：通过 [XSharedPreferences] 读取（默认只读，避免跨进程写冲突）。
 */
object Settings {
    private const val KEY_JSON = "settings_json"

    @Volatile
    private var cached: DanmakuSettings? = null

    @Volatile
    private var cacheTime: Long = 0L

    private const val CACHE_TTL_MS = 3000L

    /** 被注入进程侧读取（可缓存 3 秒） */
    fun load(force: Boolean = false): DanmakuSettings {
        val now = System.currentTimeMillis()
        val c = cached
        if (!force && c != null && now - cacheTime < CACHE_TTL_MS) return c
        val loaded = try {
            readFromXSharedPrefs() ?: DanmakuSettings()
        } catch (t: Throwable) {
            Log.w("读取设置失败，使用默认值", t)
            DanmakuSettings()
        }
        cached = loaded
        cacheTime = now
        return loaded
    }

    fun invalidate() {
        cached = null
        cacheTime = 0L
    }

    private fun readFromXSharedPrefs(): DanmakuSettings? {
        // 单参数构造函数使用模块自身的 SharedPreferences 文件（Vector / LSPosed 负责跨进程读取）
        val xsp = XSharedPreferences(DanmakuSettings.PREF_NAME)
        if (!xsp.contains(KEY_JSON)) {
            Log.i("模块设置不存在（首次使用请在模块 App 中保存一次设置）")
            return null
        }
        xsp.reload()
        val json = xsp.getString(KEY_JSON, null) ?: return null
        return SettingsCodec.decode(json)
    }

    // ---- 模块 App 进程侧 ----

    fun loadLocal(context: Context): DanmakuSettings {
        val sp = context.getSharedPreferences(DanmakuSettings.PREF_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_JSON, null) ?: return DanmakuSettings()
        return SettingsCodec.decode(json)
    }

    fun saveLocal(context: Context, settings: DanmakuSettings) {
        val sp = context.getSharedPreferences(DanmakuSettings.PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_JSON, SettingsCodec.encode(settings)).apply()
        invalidate()
    }
}
