package com.b2y.danmaku.core

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.b2y.danmaku.bili.Bangumi
import com.b2y.danmaku.bili.BiliApi
import com.b2y.danmaku.bili.BiliException
import com.b2y.danmaku.bili.BiliSearchResult
import com.b2y.danmaku.bili.DanmakuEntry
import com.b2y.danmaku.danmaku.DanmakuItem
import com.b2y.danmaku.hook.ActivityWatcher
import com.b2y.danmaku.hook.PlaybackClockHolder
import com.b2y.danmaku.hook.ShortsDetector
import com.b2y.danmaku.hook.VideoSurfaceTracker
import com.b2y.danmaku.hook.fingerprint.PlayerFingerprintHook
import com.b2y.danmaku.ui.DanmakuOverlay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话控制器：把「YouTube 当前播放的视频」翻译成「B 站弹幕」并推给浮层。
 *
 * 数据流：
 * ```
 *  视频 ID (指纹 / 媒体会话元数据)
 *        ↓
 *  标题 (oEmbed 原始标题 > 媒体会话标题)
 *        ↓
 *  B 站搜索 (WBI 签名) → 匹配度过滤 → 选择 bvid
 *        ↓
 *  分段下载弹幕 (protobuf) → DanmakuItem → DanmakuView
 * ```
 */
object VideoSessionController {

    const val TARGET_PACKAGE = "com.google.android.youtube"

    /** 视频 ID 需要稳定这么久才会被接受（滑动信息流时预览不断变化 → 永远不会确认） */
    private const val VIDEO_ID_STABLE_MS = 2000L

    /** 仅凭标题匹配时的稳定时间（更长，因为误匹配代价更高） */
    private const val TITLE_STABLE_MS = 4000L

    /** 判定"在播放页"要求的画面面积占比下限 */
    private const val WATCH_SCREEN_MIN_AREA_RATIO = 0.06f

    /** 画面消失多久后认为已经离开播放页 */
    private const val WATCH_SCREEN_VALID_MS = 2500L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "b2y-network").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val clock = PlaybackClockHolder.clock

    /** 用于丢弃过期的异步结果 */
    private val generation = AtomicInteger(0)

    @Volatile
    private var overlay: DanmakuOverlay? = null

    @Volatile
    private var activity: Activity? = null

    @Volatile
    private var settings: DanmakuSettings = DanmakuSettings()

    @Volatile
    private var videoId: String? = null

    @Volatile
    private var metadataTitle: String? = null

    @Volatile
    private var metadataArtist: String? = null

    @Volatile
    private var currentBvid: String? = null

    @Volatile
    private var statusText: String = "等待识别 YouTube 视频…"

    @Volatile
    private var lastResults: List<BiliSearchResult> = emptyList()

    /** 待确认的视频 ID / 标题（必须稳定一段时间且确实在播放页才会被接受） */
    private val pendingIdLock = Any()
    private var pendingVideoId: String? = null
    private var pendingTitle: String? = null

    /** 当前显示中的「选择要同步的 B 站视频」弹窗（进入 Shorts 时要能主动关掉） */
    @Volatile
    private var chooserDialog: AlertDialog? = null

    // ------------------------------------------------------------------ 对外接口

    fun onActivityResumed(act: Activity, ov: DanmakuOverlay) {
        activity = act
        overlay = ov
        settings = Settings.load(force = true)
        ov.applySettings(settings)
        ov.setStatusProvider { statusText }
        // 已经识别过视频时（例如旋转屏幕后重新挂载），把已有弹幕补上
        val id = videoId
        if (id != null && ov.danmakuCount() == 0 && settings.autoLoad) {
            startResolve(id, ov)
        }
    }

    fun onOverlayDetached() {
        overlay = null
        activity = null
        // 弹窗挂在 Activity 上，浮层都没了就不该留着
        main.post { dismissChooser() }
    }

    fun onMediaMetadata(
        title: String?,
        mediaId: String?,
        artist: String?,
        durationMs: Long
    ) {
        if (!title.isNullOrBlank()) metadataTitle = title
        if (!artist.isNullOrBlank()) metadataArtist = artist
        Log.d("元数据: title=$title mediaId=$mediaId artist=$artist durationMs=$durationMs")

        // 部分版本的 YouTube 会把视频 ID 放进 METADATA_KEY_MEDIA_ID
        val candidate = mediaId?.trim()
        if (candidate != null && looksLikeYouTubeId(candidate)) {
            onVideoIdFromFingerprint(candidate)
            return
        }

        // 没有视频 ID 时的兜底：**仅当 DEX 指纹不可用**（说明该版本拿不到 ID）才尝试纯标题匹配，
        // 而且同样要经过"稳定 + 确实在播放页"的确认，避免刷首页时被预览视频带偏。
        if (videoId != null) return
        if (!PlayerFingerprintHook.videoIdHookActive && settings.autoLoad && !title.isNullOrBlank()) {
            scheduleTitleOnlyResolve(title)
        }
    }

    /**
     * 指纹给出的视频 ID 只是"候选"，必须经过确认才会真正加载弹幕。
     *
     * 原因：YouTube 会为首页信息流里的自动播放预览、预加载的下一个视频、Shorts 等批量解析
     * player response，这些都会命中同一个指纹。如果直接接受，用户在首页上下滑动时就会被
     * 莫名其妙地匹配到 B 站视频。
     *
     * 确认条件：
     * 1. 该 ID 在 [VIDEO_ID_STABLE_MS] 内没有被别的 ID 顶替（滑动时预览不断变化 → 永远不确认）；
     * 2. 当前确实处于播放页（存在面积足够大的视频画面）。
     */
    fun onVideoIdFromFingerprint(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        if (trimmed == videoId) return
        synchronized(pendingIdLock) {
            if (pendingVideoId == trimmed) return
            pendingVideoId = trimmed
        }
        Log.d("候选视频 ID：$trimmed（等待确认）")
        main.removeCallbacks(confirmPendingId)
        main.postDelayed(confirmPendingId, VIDEO_ID_STABLE_MS)
    }

    private val confirmPendingId = Runnable { confirmPendingVideoId() }

    private fun confirmPendingVideoId() {
        val id = synchronized(pendingIdLock) { pendingVideoId } ?: return
        if (id == videoId) return
        if (!isOnWatchScreen()) {
            Log.i(
                "忽略非播放页的视频 ID：$id（画面占比 " +
                    "${(VideoSurfaceTracker.lastChosenAreaRatio * 100).toInt()}%）"
            )
            return
        }
        if (skipBecauseShorts()) return
        acceptVideoId(id)
    }

    private fun acceptVideoId(id: String) {
        videoId = id
        currentBvid = null
        lastResults = emptyList()
        // 换了视频，Shorts 标签状态缓存作废（上下滑切换短视频时 Activity 不会重建）
        ShortsDetector.invalidateTabCache()
        Log.i("确认当前 YouTube 视频: $id")
        statusText = "已识别视频：$id"
        val ov = overlay ?: return
        main.post {
            ov.clearDanmaku()
            ov.showToast("B2Y: 识别到视频 $id")
        }
        if (settings.autoLoad) startResolve(id, ov)
    }

    /** 当前是否处于播放页：最近找到过画面，且画面足够大（排除列表里的小窗预览） */
    private fun isOnWatchScreen(): Boolean {
        val since = android.os.SystemClock.elapsedRealtime() - VideoSurfaceTracker.lastFoundRealtimeMs
        if (since > WATCH_SCREEN_VALID_MS) return false
        return VideoSurfaceTracker.lastChosenAreaRatio >= WATCH_SCREEN_MIN_AREA_RATIO
    }

    // ------------------------------------------------------------------ Shorts 屏蔽

    /** 判定 Shorts 时用哪个 Activity：优先前台 Activity（浮层没挂上时 `activity` 可能还是 null） */
    private fun shortsCheckActivity(): android.app.Activity? = activity ?: ActivityWatcher.foregroundActivity()

    /**
     * 「Shorts 里也匹配弹幕」的**最终生效值**。
     *
     * 取值优先级：
     * 1. **被注入进程自己维护的运行时开关**（浮层里的全局开关写的那个文件）—— 这条路径不经过
     *    跨进程共享，实测最可靠；
     * 2. 模块 App 保存的设置（经 [Settings.load] / XSharedPreferences 读到的
     *    `matchInShorts`）—— 但如果跨进程读取失效，这里可能一直是旧值。
     *
     * 用户实测反馈过：设置页里取消勾选后 YouTube 进程读到的仍是旧值（诊断信息显示
     * 「设置=允许匹配」）。所以运行时开关优先，浮层开关一开一关必定生效。
     */
    fun matchInShorts(): Boolean =
        SettingsCodec.resolveMatchInShorts(Settings.loadRuntimeMatchInShorts(), settings.matchInShorts)

    /** 供控制面板显示开关状态 */
    fun isMatchInShortsEnabled(): Boolean = matchInShorts()

    /**
     * 浮层全局开关：立即生效，并落盘到被注入进程自己的 SharedPreferences
     * （同时尽量写回模块设置，保持两边显示一致）。
     *
     * @return 生效后的值
     */
    fun setMatchInShorts(value: Boolean): Boolean {
        Settings.saveRuntimeMatchInShorts(value)
        settings = settings.copy(matchInShorts = value)
        Log.i("Shorts 匹配开关已设为 $value（全局，立即生效）")
        val ov = overlay
        if (ov != null) {
            val s = settings
            main.post { ov.applySettings(s) }
        }
        if (value) {
            setStatus("已开启：Shorts 里也会匹配并显示弹幕")
        } else {
            setStatus("已关闭：Shorts 里不再匹配弹幕")
            // 关掉时立刻收掉现场：弹窗 + 已加载的弹幕
            if (isShortsBlockedNow()) dismissShortsBlockedUi()
        }
        return value
    }

    /**
     * 当前是否应该屏蔽（Shorts 且「Shorts 也匹配」是关的）。
     *
     * 额外要求「确实在看播放页」（[isOnWatchScreen]）：这层保护是为了避免首页信息流里的
     * 竖屏预览、或上一个播放页残留的画面尺寸被误判成 Shorts，从而把正常视频的弹幕也停掉。
     */
    private fun isShortsBlockedNow(): Boolean {
        if (matchInShorts()) return false
        if (!isOnWatchScreen()) return false
        return ShortsDetector.isShortsActive(shortsCheckActivity())
    }

    /**
     * 自动匹配的唯一闸门：命中屏蔽时放弃本次自动搜索，**并且把已经弹出来的
     * 「选择要同步的 B 站视频」关掉、清空弹幕**。
     *
     * 后面的部分很关键：搜索是异步的，等结果回来时用户可能已经滑进 Shorts 了；
     * 只在「发起搜索」那一刻拦截的话，对话框还是会冒出来（实测就是这个问题）。
     *
     * 注意：**只拦自动流程**。控制面板里的「粘贴 B 站链接 / 搜索关键词 / 番剧模式」是用户
     * 明确的手动意图，不做拦截。
     */
    private fun skipBecauseShorts(): Boolean {
        if (!isShortsBlockedNow()) return false
        Log.i("Shorts 已屏蔽自动匹配（${ShortsDetector.lastReason}）")
        dismissShortsBlockedUi()
        setStatus("当前是 Shorts，已跳过弹幕匹配（要强制加载可在悬浮面板里手动粘贴 B 站链接）")
        return true
    }

    /** 把「因为进了 Shorts 而不该存在」的界面收掉：选择弹窗 + 已加载的弹幕 */
    private fun dismissShortsBlockedUi() {
        dismissChooser()
        val ov = overlay
        if (ov != null && ov.danmakuCount() > 0) {
            currentBvid = null
            main.post { ov.clearDanmaku() }
        }
    }

    /**
     * 在「要显示选择弹窗」的最后一步再确认一次是否是 Shorts。
     *
     * 与 [skipBecauseShorts] 的区别是：这个在 `main.post {}` 之后执行，是最贴近用户看到
     * 界面的那一层，作为兜底。
     */
    private fun shouldBlockChooser(): Boolean {
        if (!isShortsBlockedNow()) return false
        Log.i("选择弹窗被 Shorts 拦截（${ShortsDetector.lastReason}）")
        dismissShortsBlockedUi()
        setStatus("当前是 Shorts，已跳过弹幕匹配")
        return true
    }

    /** 供控制面板展示：当前是否因为 Shorts 而停用了弹幕 */
    fun isShortsBlocked(): Boolean = isShortsBlockedNow()

    fun shortsReason(): String = ShortsDetector.lastReason

    private fun scheduleTitleOnlyResolve(title: String) {
        synchronized(pendingIdLock) {
            if (pendingTitle == title) return
            pendingTitle = title
        }
        Log.d("候选标题：$title（等待确认）")
        main.removeCallbacks(confirmPendingTitle)
        main.postDelayed(confirmPendingTitle, TITLE_STABLE_MS)
    }

    private val confirmPendingTitle = Runnable {
        val t = synchronized(pendingIdLock) { pendingTitle } ?: return@Runnable
        if (videoId != null) return@Runnable
        if (!isOnWatchScreen()) return@Runnable
        if (skipBecauseShorts()) return@Runnable
        statusText = "仅凭标题识别：$t"
        startTitleOnlyResolve(t)
    }

    /** 手动重新搜索 */
    fun retrySearch() {
        val ov = overlay ?: return
        val id = videoId
        if (id != null) {
            startResolve(id, ov, force = true)
        } else {
            val t = metadataTitle
            if (t != null) startTitleOnlyResolve(t, force = true)
            else toast("尚未识别到 YouTube 视频")
        }
    }

    /** 手动搜索关键词 */
    fun searchKeyword(keyword: String) {
        if (keyword.isBlank()) return
        val ov = overlay ?: return
        statusText = "搜索：$keyword"
        val gen = generation.incrementAndGet()
        executor.execute {
            try {
                val api = BiliApi(settings.sessData)
                val results = api.searchAllV2(keyword)
                if (gen != generation.get()) return@execute
                if (results.isEmpty()) {
                    setStatus("未找到「$keyword」相关的 B 站视频")
                    toast("未找到相关视频")
                    return@execute
                }
                lastResults = results
                if (results.size == 1) {
                    downloadAndApply(api, results[0].bvid, ov, gen)
                } else {
                    main.post { showChooser(results, ov) }
                }
            } catch (t: Throwable) {
                Log.e("手动搜索失败", t)
                setStatus("搜索失败：${t.message}")
            }
        }
    }

    /** 手动加载 B 站链接 / bvid / av 号 */
    fun loadManual(input: String) {
        val ov = overlay ?: return
        val gen = generation.incrementAndGet()
        executor.execute {
            try {
                val api = BiliApi(settings.sessData)
                val bvid = api.parseBvid(input)
                if (bvid == null) {
                    setStatus("无法解析：$input")
                    toast("无法解析输入的 B 站链接")
                    return@execute
                }
                downloadAndApply(api, bvid, ov, gen)
            } catch (t: Throwable) {
                Log.e("手动加载失败", t)
                setStatus("加载失败：${t.message}")
            }
        }
    }

    /** 番剧模式：按《标题》第 N 话 查找 */
    fun loadBangumi(title: String, episode: Int) {
        val ov = overlay ?: return
        val gen = generation.incrementAndGet()
        executor.execute {
            try {
                val api = BiliApi(settings.sessData)
                val info = Bangumi.findEpisode(api, title, episode)
                if (info == null) {
                    setStatus("未找到番剧：$title 第${episode}话")
                    return@execute
                }
                downloadAndApply(api, info.bvid, ov, gen)
            } catch (t: Throwable) {
                Log.e("番剧加载失败", t)
                setStatus("番剧加载失败：${t.message}")
            }
        }
    }

    /** 时间轴偏移微调（毫秒） */
    fun adjustOffset(deltaMs: Int) {
        val ov = overlay ?: return
        ov.adjustTimeOffset(deltaMs)
        statusText = "时间轴偏移：${ov.currentTimeOffsetMs()} ms"
    }

    fun reloadSettings() {
        settings = Settings.load(force = true)
        Log.i("设置已加载: $settings")
        val ov = overlay ?: return
        main.post { ov.applySettings(settings) }
    }

    fun currentVideoId(): String? = videoId

    fun currentTitle(): String? = metadataTitle

    fun currentBvid(): String? = currentBvid

    fun status(): String = statusText

    fun lastResults(): List<BiliSearchResult> = lastResults

    fun settingsSnapshot(): DanmakuSettings = settings

    // ------------------------------------------------------------------ 内部流程

    private fun startResolve(id: String, ov: DanmakuOverlay, force: Boolean = false) {
        if (skipBecauseShorts()) return
        val gen = generation.incrementAndGet()
        if (force) lastResults = emptyList()
        setStatus("正在识别标题…")
        executor.execute {
            try {
                val api = BiliApi(settings.sessData)
                val manual = settings.manualBvid.trim()
                if (manual.isNotEmpty()) {
                    val bvid = api.parseBvid(manual)
                    if (bvid != null) {
                        downloadAndApply(api, bvid, ov, gen)
                        return@execute
                    }
                }
                val title = resolveTitle(api, id)
                if (title.isNullOrBlank()) {
                    setStatus("无法获取视频标题（可在悬浮面板中手动搜索或粘贴 B 站链接）")
                    return@execute
                }
                if (gen != generation.get()) return@execute
                setStatus("标题：$title")
                searchAndApply(api, title, ov, gen, id)
            } catch (t: Throwable) {
                Log.e("自动识别失败", t)
                setStatus("自动识别失败：${t.message}")
            }
        }
    }

    private fun startTitleOnlyResolve(title: String, force: Boolean = false) {
        val ov = overlay ?: return
        val gen = generation.incrementAndGet()
        if (force) lastResults = emptyList()
        executor.execute {
            try {
                val api = BiliApi(settings.sessData)
                searchAndApply(api, title, ov, gen, null)
            } catch (t: Throwable) {
                Log.e("标题搜索失败", t)
                setStatus("标题搜索失败：${t.message}")
            }
        }
    }

    /**
     * 优先使用 YouTube oEmbed 返回的**原始标题**（未翻译），匹配率显著更高；
     * 失败时回退到媒体会话里的标题。
     */
    private fun resolveTitle(api: BiliApi, id: String): String? {
        val oembed = if (settings.preferOembedTitle) {
            try {
                api.fetchYouTubeTitle(id)
            } catch (t: Throwable) {
                Log.w("oEmbed 标题获取失败", t)
                null
            }
        } else null
        return oembed ?: metadataTitle
    }

    private fun searchAndApply(
        api: BiliApi,
        title: String,
        ov: DanmakuOverlay,
        gen: Int,
        youtubeVideoId: String?
    ) {
        // 番剧频道识别：《标题》第N话：
        val bangumi = Bangumi.parseBangumiTitle(title)
        if (bangumi != null) {
            setStatus("识别为番剧：${bangumi.first} 第${bangumi.second}话")
            val info = try {
                Bangumi.findEpisode(api, bangumi.first, bangumi.second)
            } catch (t: Throwable) {
                Log.w("番剧查找失败，回退到普通搜索", t)
                null
            }
            if (info != null && gen == generation.get()) {
                downloadAndApply(api, info.bvid, ov, gen)
                return
            }
        }

        val results = api.searchAllV2(title)
        if (gen != generation.get()) return
        if (results.isEmpty()) {
            setStatus("B 站未找到匹配视频：$title")
            return
        }
        lastResults = results

        val threshold = settings.matchThreshold.coerceIn(0, 100) / 100f
        val matched = results.filter { it.highlightRatio >= threshold }
        val pool = if (matched.isNotEmpty()) matched else results.take(3)

        when {
            matched.size == 1 -> {
                setStatus("匹配到唯一结果，正在下载弹幕…")
                downloadAndApply(api, matched[0].bvid, ov, gen)
            }
            matched.size > 1 && settings.multiMatchMode == "mostDanmaku" -> {
                val best = matched.maxByOrNull { it.danmaku }!!
                setStatus("匹配到 ${matched.size} 个结果，自动选择弹幕最多：${best.title}")
                downloadAndApply(api, best.bvid, ov, gen)
            }
            else -> {
                setStatus(
                    if (matched.isEmpty()) "没有结果达到匹配阈值（${settings.matchThreshold}%），请手动选择"
                    else "匹配到 ${matched.size} 个结果，请选择"
                )
                main.post { if (gen == generation.get()) showChooser(pool, ov) }
            }
        }
    }

    private fun downloadAndApply(api: BiliApi, bvid: String, ov: DanmakuOverlay, gen: Int) {
        try {
            setStatus("正在下载弹幕：$bvid")
            val info = api.getVideoInfo(bvid)
            var entries: List<DanmakuEntry> = api.fetchDanmaku(info)
            if (gen != generation.get()) return

            if (entries.size > settings.maxDanmakuCount) {
                // 弹幕过多时优先保留权重高的（B 站 weight 越高越"重要"），再按时间恢复顺序
                entries = entries.sortedByDescending { it.weight }
                    .take(settings.maxDanmakuCount)
                    .sortedBy { it.timeMs }
            }

            val items = entries.map {
                DanmakuItem(
                    timeMs = it.timeMs,
                    text = it.text,
                    color = it.color,
                    mode = it.mode,
                    weight = it.weight
                )
            }
            currentBvid = bvid
            main.post {
                if (gen != generation.get()) return@post
                // 最后一道闸：下载期间可能已经滑进 Shorts 了，这时候不能把弹幕贴上去
                if (isShortsBlockedNow()) {
                    Log.i("弹幕下载完成时已在 Shorts，丢弃本次结果（${ShortsDetector.lastReason}）")
                    ov.clearDanmaku()
                    currentBvid = null
                    setStatus("当前是 Shorts，已丢弃刚下载的弹幕")
                    return@post
                }
                ov.setDanmaku(items)
                clock.reset()
                ov.showToast("B2Y: ${info.title}（${items.size} 条弹幕）")
            }
            setStatus("已加载「${info.title}」共 ${items.size} 条弹幕（$bvid）")
        } catch (e: BiliException) {
            Log.e("下载弹幕失败", e)
            setStatus("下载弹幕失败：${e.message}")
        } catch (t: Throwable) {
            Log.e("下载弹幕异常", t)
            setStatus("下载弹幕异常：${t.message}")
        }
    }

    private fun showChooser(results: List<BiliSearchResult>, ov: DanmakuOverlay) {
        // 最后一道闸：搜索是异步的，结果回来时用户可能已经滑进 Shorts 了
        if (shouldBlockChooser()) return
        val act = shortsCheckActivity() ?: return
        if (act.isFinishing) return
        dismissChooser()
        val labels = results.map {
            "${it.title}\n${it.author} · ${it.danmaku} 弹幕 · 匹配 ${(it.highlightRatio * 100).toInt()}%"
        }.toTypedArray()
        try {
            val dialog = AlertDialog.Builder(act)
                .setTitle("选择要同步的 B 站视频")
                .setItems(labels) { _, which ->
                    val bvid = results[which].bvid
                    val gen = generation.incrementAndGet()
                    executor.execute {
                        try {
                            downloadAndApply(BiliApi(settings.sessData), bvid, ov, gen)
                        } catch (t: Throwable) {
                            Log.e("选择后下载失败", t)
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnDismissListener { if (chooserDialog === dialog) chooserDialog = null }
            chooserDialog = dialog
            dialog.show()
        } catch (t: Throwable) {
            Log.w("显示选择弹窗失败", t)
        }
    }

    private fun dismissChooser() {
        val d = chooserDialog ?: return
        chooserDialog = null
        try {
            d.dismiss()
        } catch (t: Throwable) {
            Log.w("关闭旧的选择弹窗失败", t)
        }
    }

    /** 浮层刷新时调用：如果已经滑进 Shorts，把残留的选择弹窗收掉 */
    fun onOverlayTick() {
        if (matchInShorts()) return
        if (chooserDialog == null) return
        if (isShortsBlockedNow()) {
            Log.i("浮层轮询发现已进入 Shorts，关闭选择弹窗")
            dismissShortsBlockedUi()
        }
    }

    private fun setStatus(text: String) {
        statusText = text
        Log.i(text)
        main.post { overlay?.refreshStatus() }
    }

    private fun toast(text: String) {
        main.post { overlay?.showToast(text) }
    }

    private fun looksLikeYouTubeId(s: String): Boolean {
        if (s.length != 11) return false
        return s.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
