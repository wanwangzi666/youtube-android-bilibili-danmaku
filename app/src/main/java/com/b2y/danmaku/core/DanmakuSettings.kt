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

    // ---- 被注入进程侧的「Shorts 匹配」运行时开关 ----

    /**
     * 自己维护一个独立的 SharedPreferences 文件来存这个开关。
     *
     * 为什么不直接用 [saveLocal] 那份 JSON：那份是模块 App 进程写的，被注入进程只能通过
     * [XSharedPreferences] 读，实测在部分 Vector / LSPosed / Android 版本组合下**读不到最新值**
     * （用户反馈：设置页里取消了勾选，YouTube 进程里读到的一直是旧值）。
     * 这个文件由被注入进程自己写、自己读，不依赖任何跨进程机制，因此一定同步。
     */
    private const val RUNTIME_PREF_NAME = "b2y_runtime"

    private const val KEY_MATCH_IN_SHORTS = "match_in_shorts"

    /** 总开关：是否匹配并显示弹幕（浮层面板里那个全局开关） */
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var runtimeMatchInShorts: Boolean? = null

    @Volatile
    private var runtimeEnabled: Boolean? = null

    @Volatile
    private var runtimeReadAtMs: Long = 0L

    /** 运行时开关的重复读取间隔（浮层每 250ms 会问一次，这里挡一下） */
    private const val RUNTIME_TTL_MS = 800L

    /**
     * 读取被注入进程自己的「Shorts 里也匹配」开关；没设置过返回 null。
     */
    fun loadRuntimeMatchInShorts(): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = runtimeMatchInShorts
        if (cached != null && now - runtimeReadAtMs < RUNTIME_TTL_MS) return cached
        val v = readRuntimeBoolean(KEY_MATCH_IN_SHORTS)
        runtimeMatchInShorts = v
        runtimeReadAtMs = now
        return v
    }

    /** 读取被注入进程自己的「总开关」；没设置过返回 null。 */
    fun loadRuntimeEnabled(): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = runtimeEnabled
        if (cached != null && now - runtimeReadAtMs < RUNTIME_TTL_MS) return cached
        val v = readRuntimeBoolean(KEY_ENABLED)
        runtimeEnabled = v
        runtimeReadAtMs = now
        return v
    }

    private fun readRuntimeBoolean(key: String): Boolean? = try {
        val xsp = XSharedPreferences(RUNTIME_PREF_NAME)
        xsp.reload()
        if (xsp.contains(key)) xsp.getBoolean(key, false) else null
    } catch (t: Throwable) {
        Log.w("读取运行时开关 $key 失败", t)
        null
    }

    /** 把两个运行时开关一起写盘（总开关与 Shorts 开关共用一份文件） */
    private fun writeRuntime(prefs: Map<String, Boolean>) {
        try {
            val ctx = currentApplication()
            if (ctx == null) {
                Log.w("拿不到 Application，运行时开关只在本次进程内生效")
                return
            }
            val editor = ctx.getSharedPreferences(RUNTIME_PREF_NAME, Context.MODE_PRIVATE).edit()
            for ((k, v) in prefs) editor.putBoolean(k, v)
            editor.commit()
            Log.i("运行时开关已保存: $prefs")
        } catch (t: Throwable) {
            Log.w("保存运行时开关失败", t)
        }
    }

    /** 运行时开关是否已经被设置过（浮层开关或开机时同步过） */
    fun runtimeMatchInShortsLoaded(): Boolean = runtimeMatchInShorts != null

    fun runtimeEnabledLoaded(): Boolean = runtimeEnabled != null

    /**
     * 写入被注入进程自己的「Shorts 里也匹配」开关（浮层的全局开关用这个）。
     *
     * 只写这一份文件：模块 App 的读取入口 [loadLocalMatchInShorts] 会优先读它，
     * 所以两个入口显示的状态天然一致，不需要（也不应该）去改模块 App 的那份 JSON ——
     * 那条跨进程写入路径正是实测不可靠的地方。
     */
    fun saveRuntimeMatchInShorts(value: Boolean) {
        runtimeMatchInShorts = value
        runtimeReadAtMs = android.os.SystemClock.elapsedRealtime()
        writeRuntime(mapOf(KEY_MATCH_IN_SHORTS to value))
    }

    /**
     * 写入被注入进程自己的「总开关」（浮层面板里的全局开关用这个）。
     *
     * 同样只写运行时这一份：模块 App 的读取入口 [loadLocalEnabled] 会优先读它。
     */
    fun saveRuntimeEnabled(value: Boolean) {
        runtimeEnabled = value
        runtimeReadAtMs = android.os.SystemClock.elapsedRealtime()
        writeRuntime(mapOf(KEY_ENABLED to value))
    }

    /** 拿到宿主 App 的 Context（由 [de.robv.android.xposed.IXposedHookLoadPackage] 侧注入） */
    private fun currentApplication(): Context? = applicationRef

    @Volatile
    private var applicationRef: Context? = null

    fun attachApplication(context: Context) {
        applicationRef = context
    }

    // ---- 模块 App 进程侧 ----

    /**
     * 模块 App 侧读 [RUNTIME_PREF_NAME] 里的布尔开关；文件里没有该键时返回 fallback。
     */
    private fun loadLocalRuntimeBoolean(context: Context, key: String, fallback: Boolean): Boolean =
        try {
            val sp = context.getSharedPreferences(RUNTIME_PREF_NAME, Context.MODE_PRIVATE)
            if (sp.contains(key)) sp.getBoolean(key, fallback) else fallback
        } catch (t: Throwable) {
            fallback
        }

    private fun saveLocalRuntimeBoolean(context: Context, key: String, value: Boolean) {
        try {
            context.getSharedPreferences(RUNTIME_PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit()
            Log.i("模块设置侧已保存运行时开关 $key = $value")
        } catch (t: Throwable) {
            Log.w("保存运行时开关 $key 失败", t)
        }
    }

    /** 模块 App 侧读「Shorts 里也匹配」的最终生效值 */
    fun loadLocalMatchInShorts(context: Context, fallback: Boolean): Boolean =
        loadLocalRuntimeBoolean(context, KEY_MATCH_IN_SHORTS, fallback)

    /** 模块 App 侧读「总开关」的最终生效值 */
    fun loadLocalEnabled(context: Context, fallback: Boolean): Boolean =
        loadLocalRuntimeBoolean(context, KEY_ENABLED, fallback)

    /**
     * 模块 App 侧写「Shorts 里也匹配」。
     *
     * 除了 JSON 配置（由 [saveLocal] 负责），额外写一份 [RUNTIME_PREF_NAME]：
     * 被注入进程优先读这一份，**这次一定是同步的** —— 同一个文件、同一个 app 的私有目录，
     * 不依赖任何跨进程机制。
     */
    fun saveLocalMatchInShorts(context: Context, value: Boolean) =
        saveLocalRuntimeBoolean(context, KEY_MATCH_IN_SHORTS, value)

    /** 模块 App 侧写「总开关」，同样额外写一份运行时副本 */
    fun saveLocalEnabled(context: Context, value: Boolean) =
        saveLocalRuntimeBoolean(context, KEY_ENABLED, value)

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
