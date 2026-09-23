package com.b2y.danmaku.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.b2y.danmaku.core.DanmakuSettings
import com.b2y.danmaku.core.Log
import com.b2y.danmaku.core.PlaybackClock

/**
 * 弹幕渲染 View。
 *
 * - 不接受触摸事件（`isClickable = false`，[onTouchEvent] 返回 false），保证不阻塞 YouTube 的播放器操作；
 * - 通过 [PlaybackClock] 取当前视频时间，因此暂停 / 倍速 / 拖动天然同步（渲染位置只由时间推导）；
 * - 只负责"画"，轨道与碰撞全部交给 [DanmakuEngine]。
 *
 * 使用方式：用 `LayoutParams(MATCH_PARENT, MATCH_PARENT)` 把本 View 覆盖在播放器容器之上，
 * 依次调用 [setPlaybackClock] / [setSettings]，然后 [setDanmaku]。
 */
class DanmakuView(context: Context) : View(context) {

    // ------------------------------------------------------------------
    // 绘制资源（全部复用，onDraw 中零分配）
    // ------------------------------------------------------------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        isSubpixelText = true
        // 注意：这里**不用** setShadowLayer。
        // 带模糊半径的阴影会让每个字形都走一遍 blur mask filter，弹幕一多帧率会掉到个位数，
        // 表现出来就是"弹幕不是滑进来，而是先闪两三个字再突然出现"。
        // 改用一次描边 + 一次填充（见 onDraw），效果相近但便宜得多。
    }

    /** 描边画笔：给文字加一圈深色轮廓，保证亮色画面上白字可读 */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeJoin = Paint.Join.ROUND
        isSubpixelText = true
        color = 0xCC000000.toInt()
    }

    private val engineMeasurer = TextMeasurer { text, fontSizePx ->
        paint.textSize = fontSizePx
        paint.measureText(text)
    }

    /** 弹幕引擎 */
    val engine: DanmakuEngine = DanmakuEngine(engineMeasurer)

    private var clock: PlaybackClock? = null
    private var timeOffsetMs: Int = 0
    private var videoRect: Rect? = null
    private var autoInvalidate: Boolean = true
    private var settings: DanmakuSettings? = null

    private var stageWidth: Int = 0
    private var stageHeight: Int = 0

    /** 上一次推送给引擎的时间；[Long.MIN_VALUE] 表示尚未推进过 */
    private var lastTickMs: Long = Long.MIN_VALUE

    /** 上一次的"原始"时钟时间（用于识别真正的跳转），与平滑后的时间分开跟踪 */
    private var lastRawMs: Long = Long.MIN_VALUE

    /** 经过限速平滑后的时间：真正喂给引擎的时间 */
    private var smoothedMs: Long = Long.MIN_VALUE

    /** 上一次平滑的实时时刻，用于计算实际帧间隔 */
    private var lastSmoothRealtimeMs: Long = 0L

    /** 需要让引擎按当前时间做一次 seek 同步（首次推进 / 拖动 / 换时钟 / 换设置） */
    private var needSeekSync: Boolean = true

    private var loopScheduled: Boolean = false
    private var listenerRegistered: Boolean = false

    // 诊断用：实际渲染帧率
    @Volatile
    private var tickCount: Int = 0

    @Volatile
    private var tickWindowStart: Long = 0L

    @Volatile
    private var ticksPerSecond: Int = 0

    // ------------------------------------------------------------------
    // 渲染循环
    // ------------------------------------------------------------------

    private val loop = object : Runnable {
        override fun run() {
            loopScheduled = false
            if (!isAttachedToWindow) return
            if (tick()) scheduleLoop()
        }
    }

    private val clockListener = object : PlaybackClock.Listener {
        override fun onSeek(fromMs: Long, toMs: Long) {
            // 拖动进度条：清空画面，下一次推进按新位置重新同步（不重播过去的弹幕）
            needSeekSync = true
            lastTickMs = Long.MIN_VALUE
            invalidate()
            scheduleLoop()
        }

        override fun onPlayStateChanged(playing: Boolean) {
            if (playing) {
                invalidate()
                scheduleLoop()
            } else {
                // 暂停：位置由时钟锚点决定，停止循环并重绘一帧即可
                stopLoop()
                invalidate()
            }
        }

        override fun onSpeedChanged(speed: Float) {
            // 位置只由时间推导，倍速无需特殊处理
            invalidate()
        }
    }

    /**
     * 推进一帧（引擎是幂等的，重复调用同一时间不会有副作用）。
     *
     * @return 是否需要继续逐帧刷新
     */
    private fun tick(): Boolean {
        if (stageWidth <= 0 || stageHeight <= 0) return false
        val c = clock
        if (c == null || !c.isValid()) {
            engine.update(0L, stageWidth, stageHeight)
            return false
        }

        val raw = c.positionMs() + timeOffsetMs

        var jumped = needSeekSync
        if (!jumped && lastRawMs != Long.MIN_VALUE) {
            val delta = raw - lastRawMs
            // 只有"真正的跳转"才重排：轻微回退（时钟采样抖动）必须忽略，
            // 否则每帧都会触发 onSeek → 清空画面 → 重新分配轨道，表现为弹幕上下乱闪。
            jumped = delta < -BACKWARD_JUMP_TOLERANCE_MS || delta > FORWARD_JUMP_TOLERANCE_MS
            if (jumped && enableSeekLog) {
                Log.i("检测到播放位置跳变 delta=${delta}ms，重新同步弹幕")
            }
        }
        if (jumped) {
            engine.onSeek(raw)
            smoothedMs = raw
            lastSmoothRealtimeMs = 0L
            needSeekSync = false
        }
        lastRawMs = raw
        lastTickMs = raw

        engine.update(smoothTime(raw), stageWidth, stageHeight)
        recordTick()
        return autoInvalidate && (c.isPlaying || engine.activeCount > 0)
    }

    /**
     * 时间限速平滑（按实际帧间隔自适应）。
     *
     * 两个作用：
     * 1. YouTube 上报的播放时间可能是粗粒度的（几十到几百毫秒一跳）。直接喂给引擎会让弹幕位置
     *    一跳一跳，视觉上就是"先闪两三个字、然后突然出现完整弹幕"，而不是平滑滑入。
     * 2. 反过来，如果为了平滑而用固定步长，掉帧时（比如只有 3fps）弹幕会永远追不上时间轴。
     *
     * 因此允许的追赶量 = 实际帧间隔 × [MAX_CATCHUP_RATE]，并设一个下限：
     * - 60fps（间隔 16ms）→ 每帧最多推进 128ms，1 秒的跳变约 8 帧（130ms）内平滑追平；
     * - 3fps（间隔 333ms）→ 每帧最多推进 2.6 秒，一次追平，绝不落后。
     */
    private fun smoothTime(raw: Long): Long {
        val nowRealtime = android.os.SystemClock.elapsedRealtime()
        val frameInterval = if (lastSmoothRealtimeMs == 0L) {
            16L
        } else {
            (nowRealtime - lastSmoothRealtimeMs).coerceIn(1L, 500L)
        }
        lastSmoothRealtimeMs = nowRealtime

        val previous = smoothedMs
        if (previous == Long.MIN_VALUE) {
            smoothedMs = raw
            return raw
        }

        val allowed = (frameInterval * MAX_CATCHUP_RATE).toLong().coerceAtLeast(MIN_TIME_STEP_MS)
        val delta = raw - previous
        val next = when {
            delta > allowed -> previous + allowed
            delta < -allowed -> previous - allowed
            else -> raw
        }
        smoothedMs = next
        return next
    }

    private fun recordTick() {
        tickCount++
        val now = android.os.SystemClock.elapsedRealtime()
        if (tickWindowStart == 0L) {
            tickWindowStart = now
            return
        }
        val elapsed = now - tickWindowStart
        if (elapsed >= 1000L) {
            ticksPerSecond = (tickCount * 1000L / elapsed).toInt()
            tickCount = 0
            tickWindowStart = now
        }
    }

    /** 采样抖动容忍：播放中位置偶尔回退几十毫秒是正常的，不应触发重排 */
    private var enableSeekLog: Boolean = true

    private fun scheduleLoop() {
        if (loopScheduled || !isAttachedToWindow || !autoInvalidate) return
        val c = clock
        if (c == null || !c.isPlaying) return
        loopScheduled = true
        postInvalidateOnAnimation()
        postOnAnimation(loop)
    }

    private fun stopLoop() {
        if (loopScheduled) {
            removeCallbacks(loop)
            loopScheduled = false
        }
    }

    private fun registerClockListener() {
        val c = clock
        if (c == null || listenerRegistered) return
        c.addListener(clockListener)
        listenerRegistered = true
    }

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /** 设置播放时钟（未 attach 时只记住，attach 后再注册监听） */
    fun setPlaybackClock(clock: PlaybackClock) {
        if (this.clock !== clock) {
            this.clock?.removeListener(clockListener)
            listenerRegistered = false
        }
        this.clock = clock
        registerClockListener()
        needSeekSync = true
        lastTickMs = Long.MIN_VALUE
        stopLoop()
        invalidate()
        scheduleLoop()
    }

    /** 毫秒，正数表示弹幕提前出现 */
    fun setTimeOffsetMs(offsetMs: Int) {
        timeOffsetMs = offsetMs
        needSeekSync = true
        lastTickMs = Long.MIN_VALUE
        invalidate()
    }

    /** 应用模块设置（字号 sp → px、轨道间距 dp → px、不透明度等） */
    fun setSettings(settings: DanmakuSettings) {
        val previous = this.settings
        this.settings = settings
        val metrics = resources.displayMetrics
        val fontSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            settings.fontSizeSp,
            metrics
        )
        val trackSpacingPx = (settings.trackSpacingDp * metrics.density).toInt().coerceAtLeast(0)
        engine.setConfig(
            EngineConfig(
                fontSizePx = fontSizePx,
                speed = settings.speed,
                trackSpacingPx = trackSpacingPx,
                displayAreaPercent = settings.displayAreaPercent,
                showTop = settings.showTop,
                showBottom = settings.showBottom,
                weightThreshold = settings.weightThreshold,
                maxTracks = settings.maxTracks,
                opacityPercent = settings.opacity
            )
        )

        // 只有"会改变已发射弹幕外观/位置"的参数变化才需要重排画面。
        // 否则每次应用设置（例如拖动不透明度滑块、Activity 恢复）都会清屏重发，
        // 视觉上就是一次闪烁。
        val geometryChanged = previous == null ||
            previous.fontSizeSp != settings.fontSizeSp ||
            previous.trackSpacingDp != settings.trackSpacingDp ||
            previous.displayAreaPercent != settings.displayAreaPercent ||
            previous.maxTracks != settings.maxTracks ||
            previous.speed != settings.speed ||
            previous.showTop != settings.showTop ||
            previous.showBottom != settings.showBottom ||
            previous.weightThreshold != settings.weightThreshold

        if (geometryChanged) {
            needSeekSync = true
            lastTickMs = Long.MIN_VALUE
        }
        invalidate()
        scheduleLoop()
    }

    /** 载入弹幕（拿到 B 站数据后调用；条数上限裁剪由调用方负责） */
    fun setDanmaku(items: List<DanmakuItem>) {
        engine.load(items)
        needSeekSync = true
        lastTickMs = Long.MIN_VALUE
        invalidate()
        scheduleLoop()
    }

    /** 清空弹幕 */
    fun clearDanmaku() {
        engine.clear()
        needSeekSync = true
        lastTickMs = Long.MIN_VALUE
        invalidate()
    }

    /** 请求下一次推进时按当前播放位置重新同步（画面恢复 / 尺寸变化后调用） */
    fun markNeedsResync() {
        needSeekSync = true
        lastRawMs = Long.MIN_VALUE
        lastTickMs = Long.MIN_VALUE
        smoothedMs = Long.MIN_VALUE
        lastSmoothRealtimeMs = 0L
        invalidate()
        scheduleLoop()
    }

    /** 视频实际显示区域（View 坐标系）。null 表示使用整个 View。 */
    fun setVideoRect(rect: Rect?) {
        videoRect = rect?.let { Rect(it) }
        invalidate()
    }

    /** 是否自动逐帧刷新（默认 true，跟随播放状态） */
    fun setAutoInvalidate(enabled: Boolean) {
        autoInvalidate = enabled
        if (enabled) {
            scheduleLoop()
        } else {
            stopLoop()
        }
    }

    // ------------------------------------------------------------------
    // View 生命周期
    // ------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerClockListener()
        needSeekSync = true
        lastTickMs = Long.MIN_VALUE
        invalidate()
        scheduleLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        clock?.removeListener(clockListener)
        listenerRegistered = false
        // 清空画面，避免重新挂载时残留旧位置的弹幕
        engine.clear()
        needSeekSync = true
        lastTickMs = Long.MIN_VALUE
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val previousW = stageWidth
        val previousH = stageHeight
        stageWidth = w
        stageHeight = h
        // 尺英寸法变化（悬浮层每 250ms 校准一次视频区域，可能有 ±1px 抖动）不应该触发弹幕重排：
        // 引擎会在下一次 update() 里按新尺寸重算轨道与位置，旧弹幕继续保留。
        if (previousW > 0 && previousH > 0 && (kotlin.math.abs(w - previousW) > 4 || kotlin.math.abs(h - previousH) > 4)) {
            needSeekSync = true
            lastTickMs = Long.MIN_VALUE
        }
    }

    /** 触摸事件一律交给下层（YouTube 播放器） */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (stageWidth <= 0 || stageHeight <= 0) {
            val p = parent
            if (p is View && p.width > 0 && p.height > 0) {
                stageWidth = p.width
                stageHeight = p.height
            }
        }
        if (autoInvalidate && isAttachedToWindow && !loopScheduled) {
            // 兜底：任何重绘路径（系统重绘、布局变化）下都保证位置是最新的
            tick()
            scheduleLoop()
        }

        val items = engine.activeItems
        if (stageWidth <= 0 || stageHeight <= 0 || items.isEmpty()) return

        canvas.save()
        // 只在视频显示区域内绘制，弹幕不会溢出到黑边 / 评论区
        val rect = videoRect
        if (rect != null && !rect.isEmpty) {
            canvas.clipRect(rect)
        }

        val paint = this.paint
        val stroke = this.strokePaint
        val n = items.size

        // 两趟绘制：先给所有文字描边，再填充。
        // 比逐个字形用 setShadowLayer 便宜得多（后者是每帧最高的一块开销）。
        var i = 0
        while (i < n) {
            val a = items[i]
            // 完全在屏幕外的直接跳过（引擎通常已经剔除，这里再兜一层）
            if (a.x <= stageWidth && a.x + a.width >= 0f) {
                stroke.textSize = a.fontSizePx
                stroke.alpha = a.alpha
                canvas.drawText(a.text, a.x, a.y + a.fontSizePx, stroke)
            }
            i++
        }
        i = 0
        while (i < n) {
            val a = items[i]
            if (a.x <= stageWidth && a.x + a.width >= 0f) {
                paint.textSize = a.fontSizePx
                paint.color = a.color
                paint.alpha = a.alpha
                canvas.drawText(a.text, a.x, a.y + a.fontSizePx, paint)
            }
            i++
        }

        canvas.restore()
    }

    // ------------------------------------------------------------------
    // 状态查询（调试 / 宿主用）
    // ------------------------------------------------------------------

    /** 是否正在逐帧刷新 */
    val isRenderLoopRunning: Boolean get() = loopScheduled

    /** 是否已向 [PlaybackClock] 注册监听 */
    val isClockListenerRegistered: Boolean get() = listenerRegistered

    /** 当前舞台宽度（px），未布局时为 0 */
    val stageWidthPx: Int get() = stageWidth

    /** 当前舞台高度（px），未布局时为 0 */
    val stageHeightPx: Int get() = stageHeight

    /** 当前生效的设置 */
    fun currentSettings(): DanmakuSettings? = settings

    /** 当前弹幕时间（视频时间 + 偏移） */
    fun currentDanmakuTimeMs(): Long = (clock?.positionMs() ?: 0L) + timeOffsetMs

    /** 实际渲染帧率（诊断用，每秒更新一次） */
    val renderFps: Int get() = ticksPerSecond

    /** 引擎当前使用的（平滑后）弹幕时间 */
    val engineTimeMs: Long get() = smoothedMs

    /** 引擎当前画面上的弹幕条数 */
    val activeCount: Int get() = engine.activeCount

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        // 不调用 setLayerType，保持普通 View（避免额外的离屏缓冲）
        background = null
        Log.d("DanmakuView 已创建")
    }

    companion object {
        /** 默认文字颜色（#FFFFFF） */
        const val DEFAULT_TEXT_COLOR: Int = Color.WHITE

        /** 时钟采样抖动容忍：回退超过该值才认为是真的往回拖动 */
        private const val BACKWARD_JUMP_TOLERANCE_MS = 800L

        /** 播放中被跳过的时间超过该值（掉帧/后台恢复）才重新同步 */
        private const val FORWARD_JUMP_TOLERANCE_MS = 4000L

        /** 时间平滑的追赶倍率：最多用 1/8 的时间追平时钟跳变 */
        private const val MAX_CATCHUP_RATE = 8f

        /** 时间平滑的每帧最小推进量 */
        private const val MIN_TIME_STEP_MS = 60L
    }
}
