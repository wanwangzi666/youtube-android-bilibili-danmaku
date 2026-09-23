package com.b2y.danmaku.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.b2y.danmaku.core.DanmakuSettings
import com.b2y.danmaku.core.Log
import com.b2y.danmaku.core.VideoSessionController
import com.b2y.danmaku.danmaku.DanmakuItem
import com.b2y.danmaku.danmaku.DanmakuView
import com.b2y.danmaku.hook.PlaybackClockHolder
import com.b2y.danmaku.hook.VideoSurfaceTracker

/**
 * 挂在 YouTube Activity 上的弹幕浮层。
 *
 * 结构（全部叠在 `android.R.id.content` 之上）：
 * ```
 *  FrameLayout (本类)
 *   ├── DanmakuView       全屏透明，绘制弹幕，不接收触摸
 *   └── TextView "弹"     可拖动的悬浮按钮，用来打开控制面板
 * ```
 * 浮层本身不拦截触摸，因此不会影响 YouTube 播放器的操作。
 */
class DanmakuOverlay(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var danmakuView: DanmakuView? = null
    private var floatButton: TextView? = null
    private var panel: ControlPanel? = null

    private var timeOffsetMs: Int = 0
    private var statusProvider: () -> String = { "" }
    private var attached = false

    private companion object {
        /** 画面消失超过该时长就隐藏弹幕层（离开播放页） */
        const val SURFACE_LOST_HIDE_MS = 2000L
    }

    private var lastVideoRect: Rect? = null

    /** 画面消失的起始时刻；用于判断"是否已经离开播放页" */
    private var surfaceLostSince: Long = 0L

    private val rectPoller = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshVideoRect()
            main.postDelayed(this, 250L)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    fun attach() {
        if (attached) return
        val container = findContentContainer() ?: run {
            Log.w("找不到 Activity 内容容器，无法挂载浮层")
            return
        }
        val ctx = activity
        val rootView = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            clipChildren = false
        }

        val view = DanmakuView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPlaybackClock(PlaybackClockHolder.clock)
            visibility = View.VISIBLE
        }

        val button = createFloatButton()

        rootView.addView(view)
        rootView.addView(button)
        container.addView(rootView)

        root = rootView
        danmakuView = view
        floatButton = button
        attached = true

        // 立即套用一次模块设置，避免等待控制器回调期间使用默认字号/透明度
        try {
            applySettings(com.b2y.danmaku.core.Settings.load())
        } catch (t: Throwable) {
            Log.w("套用初始设置失败", t)
        }

        main.post(rectPoller)
        Log.i("弹幕浮层已挂载")
    }

    fun detach() {
        if (!attached) return
        attached = false
        main.removeCallbacks(rectPoller)
        try {
            panel?.dismiss()
        } catch (_: Throwable) {
        }
        panel = null
        try {
            root?.let { r -> (r.parent as? ViewGroup)?.removeView(r) }
        } catch (t: Throwable) {
            Log.w("移除浮层失败", t)
        }
        root = null
        danmakuView = null
        floatButton = null
    }

    fun onResume() {
        danmakuView?.setAutoInvalidate(true)
        main.removeCallbacks(rectPoller)
        main.post(rectPoller)
    }

    fun onPause() {
        danmakuView?.setAutoInvalidate(false)
        main.removeCallbacks(rectPoller)
    }

    // ------------------------------------------------------------------ 数据接口

    fun applySettings(settings: DanmakuSettings) {
        timeOffsetMs = settings.timeOffsetMs
        danmakuView?.setSettings(settings)
        floatButton?.visibility = if (settings.showFloatButton) View.VISIBLE else View.GONE
    }

    fun setDanmaku(items: List<DanmakuItem>) {
        danmakuView?.setDanmaku(items)
    }

    fun clearDanmaku() {
        danmakuView?.clearDanmaku()
    }

    fun danmakuCount(): Int = danmakuView?.engine?.loadedCount ?: 0

    fun adjustTimeOffset(deltaMs: Int) {
        timeOffsetMs += deltaMs
        danmakuView?.setTimeOffsetMs(timeOffsetMs)
    }

    fun currentTimeOffsetMs(): Int = timeOffsetMs

    fun setStatusProvider(provider: () -> String) {
        statusProvider = provider
    }

    fun refreshStatus() {
        panel?.refresh()
    }

    fun showToast(text: String) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    fun activity(): Activity = activity

    fun currentStatus(): String = statusProvider()

    // ------------------------------------------------------------------ 内部

    private fun refreshVideoRect() {
        val view = danmakuView ?: return
        val rootView = root ?: return
        val surfaceRect = VideoSurfaceTracker.findVideoRectOnScreen(activity)
        if (surfaceRect == null) {
            // 找不到画面（返回首页 / 退出播放页）：一段时间后隐藏整个弹幕层，
            // 既避免弹幕飘在首页信息流上，也省下逐帧重绘的开销。
            val now = android.os.SystemClock.elapsedRealtime()
            if (surfaceLostSince == 0L) surfaceLostSince = now
            if (now - surfaceLostSince > SURFACE_LOST_HIDE_MS && view.visibility == View.VISIBLE) {
                view.visibility = View.GONE
                view.setAutoInvalidate(false)
                Log.i("视频画面消失，隐藏弹幕层")
            }
            if (lastVideoRect != null) {
                lastVideoRect = null
                // 退化为整屏，等画面重新出现时再精确对齐
                view.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                view.setVideoRect(null)
            }
            return
        }

        surfaceLostSince = 0L
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.setAutoInvalidate(true)
            view.markNeedsResync()
            Log.i("视频画面恢复，重新显示弹幕层")
        }

        // 用 MediaSession 元数据里的视频分辨率把画面裁出来（不可用时直接用画面 bounds）
        val fitted = Rect(surfaceRect)

        val loc = IntArray(2)
        rootView.getLocationOnScreen(loc)
        val left = fitted.left - loc[0]
        val top = fitted.top - loc[1]
        val width = fitted.width()
        val height = fitted.height()
        if (width <= 0 || height <= 0) return

        // 小于 4px 的变化不重新布局，避免反复触发布局/尺寸回调
        val previous = lastVideoRect
        if (previous != null &&
            kotlin.math.abs(previous.left - left) <= 4 &&
            kotlin.math.abs(previous.top - top) <= 4 &&
            kotlin.math.abs(previous.width() - width) <= 4 &&
            kotlin.math.abs(previous.height() - height) <= 4
        ) {
            return
        }

        lastVideoRect = Rect(left, top, left + width, top + height)

        // 让弹幕舞台**就是**视频画面本身：轨道数、横向滚动距离都基于视频区域计算，
        // 这样黑边（letterbox）区域不会出现"弹幕凭空出现在半路"的问题。
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
        view.setVideoRect(null)
    }

    /** 在 [bounds] 内按 [aspect]（宽/高）居中裁出最大内接矩形 */
    private fun fitAspect(bounds: Rect, aspect: Float): Rect {
        val bw = bounds.width().toFloat()
        val bh = bounds.height().toFloat()
        if (bw <= 0f || bh <= 0f) return Rect(bounds)
        val targetW: Float
        val targetH: Float
        if (bw / bh > aspect) {
            // 容器比视频更宽：以高度为准，左右留黑边
            targetH = bh
            targetW = bh * aspect
        } else {
            targetW = bw
            targetH = bw / aspect
        }
        val left = bounds.left + ((bw - targetW) / 2f).toInt()
        val top = bounds.top + ((bh - targetH) / 2f).toInt()
        return Rect(left, top, left + targetW.toInt(), top + targetH.toInt())
    }

    /** 诊断信息（控制面板里展示，便于用户反馈问题） */
    fun diagnostics(): String {
        val v = danmakuView
        val clock = PlaybackClockHolder.clock
        return buildString {
            append("弹幕舞台：").append(v?.stageWidthPx).append("×").append(v?.stageHeightPx)
            append("  视频区域：").append(lastVideoRect?.let { "${it.width()}×${it.height()}@(${it.left},${it.top})" } ?: "全屏兜底")
            append('\n')
            append("画面来源：").append(VideoSurfaceTracker.lastChosenDescription)
            append('\n')
            append("时钟：valid=").append(clock.isValid())
                .append(" playing=").append(clock.isPlaying)
                .append(" speed=").append(clock.playbackSpeed)
                .append(" pos=").append(clock.positionMs() / 1000.0).append("s")
            append('\n')
            append("内部时间指纹：").append(if (com.b2y.danmaku.hook.fingerprint.PlayerFingerprintHook.videoTimeHookActive) "已启用" else "未启用")
                .append("  视频ID指纹：").append(if (com.b2y.danmaku.hook.fingerprint.PlayerFingerprintHook.videoIdHookActive) "已启用" else "未启用")
            append('\n')
            append("渲染帧率：").append(v?.renderFps ?: 0).append(" fps   引擎时间=")
                .append((v?.engineTimeMs ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) "-" else "${it / 1000.0}s" })
            append('\n')
            append("弹幕：已载入 ").append(v?.engine?.loadedCount ?: 0)
                .append("  在场 ").append(v?.activeCount ?: 0)
            append('\n')
            append("设置：opacity=").append(VideoSessionController.settingsSnapshot().opacity)
                .append(" fontSize=").append(VideoSessionController.settingsSnapshot().fontSizeSp)
                .append(" speed=").append(VideoSessionController.settingsSnapshot().speed)
                .append(" area=").append(VideoSessionController.settingsSnapshot().displayAreaPercent).append('%')
        }
    }

    private fun findContentContainer(): ViewGroup? {
        val byId = activity.findViewById<View>(android.R.id.content) as? ViewGroup
        if (byId != null) return byId
        return activity.window?.decorView as? ViewGroup
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatButton(): TextView {
        val density = activity.resources.displayMetrics.density
        val size = (36 * density).toInt()
        val button = TextView(activity).apply {
            text = "弹"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setBackgroundColor(0xCCFB7299.toInt())
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (72 * density).toInt()
                rightMargin = (8 * density).toInt()
            }
            isClickable = true
            isFocusable = false
            alpha = 0.75f
        }

        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var dragged = false

        button.setOnTouchListener { v, event ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) dragged = true
                    if (dragged) {
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = (startLeft + dx).coerceAtLeast(0)
                        lp.topMargin = (startTop + dy).coerceAtLeast(0)
                        v.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openPanel()
                    true
                }
                else -> false
            }
        }
        return button
    }

    private fun openPanel() {
        try {
            val p = panel ?: ControlPanel(activity, this).also { panel = it }
            p.show()
        } catch (t: Throwable) {
            Log.e("打开控制面板失败", t)
        }
    }
}
