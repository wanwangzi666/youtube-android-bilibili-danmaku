package com.b2y.danmaku.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.b2y.danmaku.core.Log
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 判断「当前是不是 YouTube 的 Shorts 短视频」。
 *
 * 为什么要判断：Shorts 的标题通常是外文短句或带一堆 hashtag，B 站几乎没有对应内容；
 * 按标题搜索只会得到 0~25% 的匹配度，弹出「选择要同步的 B 站视频」纯属打扰。
 *
 * 判断依据（任一层命中即算 Shorts，具体顺序见 [isShortsActive]）：
 *
 * 1. **Shorts 播放器视图**：`com.google.android.libraries.youtube.reel.internal.*` 里的几个类。
 *    它们是 R8 的 **-keep** 目标（类名在 YouTube 21.38.124 的 DEX 里完好保留），所以可以直接
 *    按类名在自己的 classLoader 里解析。模块不主动 hook 它们，只在判断时按需解析一次，
 *    再遍历视图树找实例。
 * 2. **底部导航「Shorts」标签选中**：用户实测最可靠的一条信号 —— Shorts 页（含全屏短视频
 *    播放器）底部导航的 Shorts 图标是高亮的。只用文字遍历框架视图树实现，不 hook YouTube
 *    内部类。为避免「从 Shorts 页点开普通视频」这类误判，还要求画面是竖屏。
 * 3. **竖屏铺满几何**：纯画面尺寸判定，完全不依赖 YouTube 实现，阈值见
 *    [MIN_SCREEN_HEIGHT_RATIO]。
 *
 * 判定结果带有 [DECAY_MS] 的保鲜期：短时间（退出播放器、视图瞬时回收）内不会因为一次探测失败
 * 就来回抖动。
 */
object ShortsDetector {

    /** YouTube 内 short 播放器相关的类（R8 keep 下来的名字，版本间相对稳定） */
    private val CANDIDATE_CLASSES = listOf(
        "com.google.android.libraries.youtube.reel.internal.player.ReelPlayerView",
        "com.google.android.libraries.youtube.reel.internal.fragment.ReelTouchCaptureView",
        "com.google.android.libraries.youtube.reel.internal.pager.ReelRecyclerView",
        "com.google.android.libraries.youtube.reel.internal.pager.ReelLinearLayoutManager"
    )

    /** 判定结果的保鲜时长（毫秒） */
    private const val DECAY_MS = 3000L

    /** 底部导航「Shorts」标签的文字（各语言下都是 "Shorts"） */
    private const val SHORTS_LABEL = "Shorts"

    /** 扫描视图树找标签的最小间隔（遍历整棵树不便宜） */
    private const val TAB_SCAN_INTERVAL_MS = 1200L

    /** 只认屏幕这个比例以下的标签，避免标题里的 "shorts" 被误判成底部导航 */
    private const val TAB_BOTTOM_REGION_RATIO = 0.5f

    /** 判断选中态时向上/向下查找的最大层数 */
    private const val MAX_TAB_ANCESTOR_DEPTH = 6
    private const val MAX_TAB_DESCENDANT_DEPTH = 3

    /** 竖屏判定容忍度：宽/高 必须小于该值才算竖屏 */
    private const val PORTRAIT_MAX_ASPECT = 0.9f

    /**
     * 画面高度至少要占屏幕高的比例。
     *
     * 0.55 是权衡出来的：
     * - Shorts 的画面在常见机型上从状态栏下方铺到底部（1080×2340 上约 1080×1860 = 80%），
     *   普通 16:9 手机上更是接近铺满；
     * - 普通播放页里的竖屏视频被「适应宽度」缩放后只有约 46% 高（1080×2340 上约 1080×1080），
     *   横屏正片约 26% 高。
     *
     * 已知残留盲区：在超长屏（宽高比 ≥ 2.1，例如 20:9）上看 9:16 的 Shorts 时，
     * 画面「适应宽度」后高度刚好掉到阈值以下，可能漏判。这类机型上依赖第 1 层（播放器视图）
     * 判定；两层都没命中时用户可以手动关掉弹幕。详见 README「已知限制」。
     */
    private const val MIN_SCREEN_HEIGHT_RATIO = 0.55f

    /**
     * 画面宽度至少要占屏幕宽的比例。
     *
     * 0.5 是为了排除首页信息流里的小窗预览，同时容忍 Shorts 里「原始比例竖屏视频」两侧
     * 的留白（9:16 视频在 20:9 手机上约 62% 宽）。
     */
    private const val MIN_SCREEN_WIDTH_RATIO = 0.5f

    private val refs: MutableList<WeakReference<View>> =
        Collections.synchronizedList(ArrayList<WeakReference<View>>())

    private var classLoader: ClassLoader? = null

    /** 惰性解析出的 Shorts 相关 View 类 */
    private var viewClasses: List<Class<*>>? = null

    /** 最近一次「确实是 Shorts」的时刻（elapsedRealtime） */
    @Volatile
    private var lastShortsRealtimeMs: Long = 0L

    /** 几何兜底使用的最近一次视频画面 */
    @Volatile
    private var lastVideoBounds: Rect? = null

    /** 最近一次判定依据（诊断用） */
    @Volatile
    var lastReason: String = "尚未判断"
        private set

    // ------------------------------------------------------------------ 安装

    fun install(cl: ClassLoader) {
        classLoader = cl
        Log.i("ShortsDetector 安装完成（候选类 ${CANDIDATE_CLASSES.size} 个）")
    }

    /**
     * 由 [VideoSurfaceTracker] 在 hook 到 SurfaceView / TextureView 构造时调用。
     * 这里只登记引用，真正的 Shorts 判断按需进行，不额外 hook YouTube 内部类。
     */
    fun registerView(view: View) {
        val classes = resolveViewClasses()
        if (classes.isEmpty() || !classes.any { it.isInstance(view) }) return
        synchronized(refs) {
            if (refs.size > 32) refs.removeAt(0)
            refs.add(WeakReference(view))
        }
        lastShortsRealtimeMs = android.os.SystemClock.elapsedRealtime()
        lastReason = "Shorts 播放器视图：${view.javaClass.name}"
        Log.i("检测到 Shorts 播放器视图：${view.javaClass.name}")
    }

    /** 浮层每轮刷新时把当前视频画面喂进来，供几何判定使用 */
    fun onVideoBounds(bounds: Rect?) {
        lastVideoBounds = bounds?.let { Rect(it) }
    }

    /** 清除「最近一次判定为 Shorts」的保鲜标记（例如确认离开 Shorts 播放器时） */
    fun reset() {
        lastShortsRealtimeMs = 0L
    }

    // ------------------------------------------------------------------ 判断

    /**
     * 当前是否在 YouTube 的 Shorts 短视频里。
     *
     * 只应在**主线程**调用（会遍历视图树）。
     *
     * 三层依据，任一成立即算 Shorts：
     *
     * 1. **Shorts 播放器视图在线** —— 最直接，但依赖那几个 `reel.internal.*` 类名没被混淆；
     * 2. **底部导航的「Shorts」标签处于选中态** —— 用户实测反馈里最稳的一条：Shorts 页面
     *    （包括全屏短视频播放器）底部导航栏的 Shorts 图标是高亮的。单独用它会有误判风险
     *    （例如从 Shorts 页点开一个普通视频），所以还要「画面是竖屏」这一条配合；
     * 3. **竖屏铺满几何** —— 完全基于画面尺寸的兜底。
     */
    fun isShortsActive(activity: Activity?): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val screen = screenBounds(activity)
        val bounds = resolveVideoBounds(activity)

        // 形状信号：竖屏画面（宽/高 < 0.9），三种依据都用得上
        val portrait = isPortrait(bounds)
        val fillsScreen = fillsScreen(bounds, screen)

        // 1) Shorts 播放器视图在线
        if (now - lastShortsRealtimeMs <= DECAY_MS && hasLiveShortsView(activity)) {
            lastShortsRealtimeMs = now
            lastReason = "Shorts 播放器视图在线"
            return true
        }

        // 2) 底部导航「Shorts」标签选中 + 竖屏画面
        val tabSelected = isShortsTabSelected(activity, screen)
        if (tabSelected && portrait) {
            lastShortsRealtimeMs = now
            lastReason = "底部「Shorts」标签选中 + 竖屏画面（${bounds?.width()}×${bounds?.height()}）"
            return true
        }

        // 3) 竖屏铺满几何
        if (portrait && fillsScreen) {
            lastShortsRealtimeMs = now
            val b = bounds!!
            lastReason =
                "竖屏铺满画面（几何判定：画面 ${b.width()}×${b.height()} / 屏幕 ${screen.width()}×${screen.height()}）"
            return true
        }

        if (now - lastShortsRealtimeMs > DECAY_MS) {
            lastReason = "非 Shorts（画面 ${bounds?.width() ?: -1}×${bounds?.height() ?: -1} / " +
                "屏幕 ${screen.width()}×${screen.height()}；Shorts 标签" +
                (if (tabSelected) "选中但画面非竖屏" else "未选中") +
                "；无 Shorts 播放器视图）"
        }
        return false
    }

    private fun isPortrait(bounds: Rect?): Boolean {
        if (bounds == null) return false
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return false
        return w.toFloat() / h.toFloat() < PORTRAIT_MAX_ASPECT
    }

    private fun fillsScreen(bounds: Rect?, screen: Rect): Boolean {
        if (bounds == null) return false
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0 || screen.width() <= 0 || screen.height() <= 0) return false
        return h >= screen.height() * MIN_SCREEN_HEIGHT_RATIO &&
            w >= screen.width() * MIN_SCREEN_WIDTH_RATIO
    }

    // ------------------------------------------------------------------ 底部导航判定

    /** 上次遍历视图树找「Shorts」标签的时刻 */
    private var lastTabScanMs: Long = 0L

    /** 缓存的扫描结果 */
    private var cachedTabSelected: Boolean = false

    /** 每次进入新界面时调用，让缓存的标签状态失效 */
    fun invalidateTabCache() {
        lastTabScanMs = 0L
        cachedTabSelected = false
    }

    /**
     * 底部导航栏里的「Shorts」标签是否处于选中态。
     *
     * 实现方式刻意保持"版本无关"：不 hook YouTube 的任何内部类，只用框架 API
     * [android.view.ViewGroup.findViewsWithText] 按可见文字找标签，再判断
     * 它自身或它的祖先是否被标记为 selected，以及它是否位于屏幕下半部分
     * （这样不会把标题/描述里的 "shorts" 误当成标签）。
     *
     * 遍历整棵视图树不便宜，所以结果缓存 [TAB_SCAN_INTERVAL_MS]。
     */
    private fun isShortsTabSelected(activity: Activity?, screen: Rect): Boolean {
        val act = activity ?: return false
        val decor = act.window?.decorView as? ViewGroup ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTabScanMs < TAB_SCAN_INTERVAL_MS) return cachedTabSelected
        lastTabScanMs = now

        val selected = try {
            scanShortsTab(decor, screen)
        } catch (t: Throwable) {
            Log.d("扫描 Shorts 标签失败: ${t.message}")
            false
        }
        cachedTabSelected = selected
        return selected
    }

    private fun scanShortsTab(decor: ViewGroup, screen: Rect): Boolean {
        val found = ArrayList<View>()
        // 先按可见文字找（底部导航通常有 "Shorts" 文字标签）
        decor.findViewsWithText(found, SHORTS_LABEL, View.FIND_VIEWS_WITH_TEXT)
        if (found.isEmpty()) {
            // 有些版本只有图标，文字靠 contentDescription 提供
            decor.findViewsWithText(found, SHORTS_LABEL, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        }
        if (found.isEmpty()) return false

        val tmp = Rect()
        val candidates = ArrayList<ShortsTabCandidate>(found.size)
        for (label in found) {
            if (!label.isShown) continue
            if (!label.getGlobalVisibleRect(tmp)) continue
            val labelText = (label as? TextView)?.text?.toString()
                ?: label.contentDescription?.toString()
            val selected = label.isSelected ||
                hasSelectedAncestor(label, decor, MAX_TAB_ANCESTOR_DEPTH) ||
                // 有些实现把选中态放在标签的兄弟节点（图标）上
                (label.parent as? ViewGroup)
                    ?.let { hasSelectedDescendant(it, MAX_TAB_DESCENDANT_DEPTH) } == true
            candidates.add(ShortsTabCandidate(labelText, tmp.centerY(), selected))
        }
        return isShortsTabSelected(candidates, screen.top, screen.height())
    }

    private fun hasSelectedAncestor(view: View, decor: View, maxDepth: Int): Boolean {
        var cur: View? = view.parent as? View
        var depth = 0
        while (cur != null && depth++ < maxDepth) {
            if (cur.isSelected) return true
            if (cur === decor) return false
            cur = cur.parent as? View
        }
        return false
    }

    private fun hasSelectedDescendant(group: ViewGroup, maxDepth: Int): Boolean {
        if (maxDepth <= 0) return false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.isSelected) return true
            if (child is ViewGroup && hasSelectedDescendant(child, maxDepth - 1)) return true
        }
        return false
    }

    /**
     * 取当前视频画面的屏幕坐标矩形。
     *
     * 优先用浮层每 250ms 喂进来的值（[onVideoBounds]）；**浮层还没跑过一轮时**直接问
     * [VideoSurfaceTracker]。这一点很关键：早期版本只看浮层喂的值，于是「浮层尚未挂载 /
     * 尚未轮询」的那段时间里判定恒为「不是 Shorts」，自动匹配就先跑起来了。
     */
    private fun resolveVideoBounds(activity: Activity?): Rect? {
        val cached = lastVideoBounds
        if (cached != null && cached.width() > 0 && cached.height() > 0) return cached
        val act = activity ?: return cached
        return try {
            VideoSurfaceTracker.findVideoRectOnScreen(act) ?: cached
        } catch (t: Throwable) {
            Log.d("直接查询画面区域失败: ${t.message}")
            cached
        }
    }

    private fun hasLiveShortsView(activity: Activity?): Boolean {
        val act = activity ?: return false
        val decor = act.window?.decorView ?: return false
        val snapshot = synchronized(refs) {
            refs.removeAll { it.get()?.isAttachedToWindow != true }
            refs.mapNotNull { it.get() }
        }
        for (v in snapshot) {
            if (!v.isShown) continue
            if (v.getGlobalVisibleRect(Rect()) && isDescendantOf(v, decor)) return true
        }
        return false
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        if (view === ancestor) return true
        var cur: View? = view.parent as? View
        var guard = 0
        while (cur != null && guard++ < 64) {
            if (cur === ancestor) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun screenBounds(activity: Activity?): Rect {
        val act = activity ?: return Rect()
        val rect = Rect()
        act.window?.decorView?.getGlobalVisibleRect(rect)
        if (rect.width() > 0 && rect.height() > 0) return rect
        val dm = act.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    // ------------------------------------------------------------------ 类解析

    private fun resolveViewClasses(): List<Class<*>> {
        viewClasses?.let { return it }
        val cl = classLoader ?: return emptyList()
        val found = ArrayList<Class<*>>()
        for (name in CANDIDATE_CLASSES) {
            try {
                found.add(Class.forName(name, false, cl))
            } catch (_: Throwable) {
                // 该类在本版本里不存在：正常情况，继续试下一个
            }
        }
        val frozen = found.toList()
        viewClasses = frozen
        if (frozen.isEmpty()) {
            Log.i("本版本未找到 Shorts 播放器类，改用竖屏全屏几何判定")
        } else {
            Log.i("Shorts 播放器类命中 ${frozen.size} 个：" + frozen.joinToString { it.simpleName })
        }
        return frozen
    }

    // ------------------------------------------------------------------ 纯函数（可单元测试）

    /**
     * 一个候选的「Shorts」标签（从视图树里扫出来的）。
     *
     * @param text 标签文字（可为 null，此时按内容描述扫到的）
     * @param centerY 标签在屏幕坐标里的垂直中心
     * @param selected 标签自身 / 祖先 / 兄弟节点是否被标记为选中
     */
    data class ShortsTabCandidate(val text: String?, val centerY: Int, val selected: Boolean)

    /**
     * 底部导航「Shorts」标签是否处于选中态（纯函数，便于单测）。
     *
     * 判定规则：**文字是 "Shorts"（忽略大小写与首尾空格）** + **位于屏幕下半部分** + **选中**。
     * 中间那条是为了排除视频标题 / 描述里出现的 "shorts" 字样。
     */
    fun isShortsTabSelected(
        candidates: List<ShortsTabCandidate>,
        screenTop: Int,
        screenHeight: Int
    ): Boolean {
        if (screenHeight <= 0) return false
        val regionStart = screenTop + screenHeight * TAB_BOTTOM_REGION_RATIO
        return candidates.any { c ->
            c.selected &&
                c.centerY >= regionStart &&
                c.text?.trim()?.equals(SHORTS_LABEL, ignoreCase = true) == true
        }
    }

    /**
     * Shorts 播放器相关的候选类名（对外暴露，便于单测在真实 APK 的 DEX 上验证这些名字
     * 确实存在 —— 换句话说，确认这一层判定依据没有因为 YouTube 版本更新而失效）。
     */
    val candidateClassNames: List<String> get() = CANDIDATE_CLASSES

    /** 几何兜底：视频画面是否为「竖屏 + 几乎占满屏幕」 */
    fun looksLikeShortsGeometry(videoBounds: Rect?, screenBounds: Rect?): Boolean {
        if (videoBounds == null || screenBounds == null) return false
        return looksLikeShortsGeometry(
            videoWidth = videoBounds.width(),
            videoHeight = videoBounds.height(),
            screenWidth = screenBounds.width(),
            screenHeight = screenBounds.height()
        )
    }

    /**
     * 几何兜底的实际判定规则（纯整数入参）。
     *
     * 之所以不直接接收 [Rect]：单元测试运行在没有真实 Android 实现的 JVM 上，
     * `android.graphics.Rect.width()` 是返回 0 的桩，无法验证。把规则抽成纯函数后，
     * 这条兜底逻辑就能在 JVM 上被完整覆盖。
     *
     * 两个条件（都满足才算 Shorts）：
     * 1. **竖屏**：宽/高 < [PORTRAIT_MAX_ASPECT] —— 一步排除所有横屏正片；
     * 2. **铺满屏幕**：高 ≥ 屏幕高 × [MIN_SCREEN_HEIGHT_RATIO] 且宽 ≥ 屏幕宽 × [MIN_SCREEN_WIDTH_RATIO]
     *    —— 排除普通播放页里的竖屏视频（上下大片黑边）与信息流小窗预览。
     */
    fun looksLikeShortsGeometry(
        videoWidth: Int,
        videoHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (videoWidth <= 0 || videoHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return false
        if (videoWidth.toFloat() / videoHeight.toFloat() >= PORTRAIT_MAX_ASPECT) return false
        return videoHeight >= screenHeight * MIN_SCREEN_HEIGHT_RATIO &&
            videoWidth >= screenWidth * MIN_SCREEN_WIDTH_RATIO
    }
}
