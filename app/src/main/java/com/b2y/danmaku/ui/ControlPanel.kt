package com.b2y.danmaku.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.b2y.danmaku.core.DanmakuSettings
import com.b2y.danmaku.core.Log
import com.b2y.danmaku.core.VideoSessionController

/**
 * 播放页内的控制面板（点击悬浮「弹」按钮打开）。
 *
 * 这里的调整是**会话内临时生效**的；需要长期保存请到模块 App 的「设置」里修改。
 */
class ControlPanel(
    private val activity: Activity,
    private val overlay: DanmakuOverlay
) {

    private var dialog: AlertDialog? = null
    private var local: DanmakuSettings = VideoSessionController.settingsSnapshot()
    private var statusView: TextView? = null
    private var diagnosticsView: TextView? = null

    fun show() {
        refreshLocal()
        val scroll = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(box)

        statusView = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, (12 * activity.resources.displayMetrics.density).toInt())
        }
        box.addView(statusView)

        box.addView(sectionTitle("识别与加载"))
        box.addView(row(
            action("重新搜索") { VideoSessionController.retrySearch(); refresh() },
            action("搜索关键词") { askText("搜索 B 站视频", "输入关键词", "") { VideoSessionController.searchKeyword(it) } }
        ))
        box.addView(row(
            action("粘贴 B 站链接") {
                askText("加载 B 站视频", "bvid / av 号 / 完整链接", "") { VideoSessionController.loadManual(it) }
            },
            action("番剧模式") {
                askText("番剧弹幕", "《标题》 例如：孤独摇滚", "") { input ->
                    askText("第几话", "集数", "1") { ep ->
                        VideoSessionController.loadBangumi(input, ep.toIntOrNull() ?: 1)
                    }
                }
            }
        ))

        box.addView(sectionTitle("时间轴"))
        box.addView(row(
            action("-0.5s") { VideoSessionController.adjustOffset(-500); syncFromOverlay(); refresh() },
            action("归零") { setOffset(0) },
            action("+0.5s") { VideoSessionController.adjustOffset(500); syncFromOverlay(); refresh() }
        ))
        box.addView(slider(
            label = "时间轴偏移（毫秒，正数=弹幕提前）",
            max = 1200,
            progress = (local.timeOffsetMs + 600).coerceIn(0, 1200),
            onChanged = { v ->
                local = local.copy(timeOffsetMs = v - 600)
                apply()
            }
        ))

        box.addView(sectionTitle("显示"))
        box.addView(slider("不透明度 %", 100, local.opacity) { v ->
            local = local.copy(opacity = v); apply()
        })
        box.addView(slider("字号 (sp)", 28, (local.fontSizeSp - 8f).toInt().coerceIn(0, 28)) { v ->
            local = local.copy(fontSizeSp = (v + 8).toFloat()); apply()
        })
        box.addView(slider("速度 ×10", 21, ((local.speed - 0.4f) * 10).toInt().coerceIn(0, 21)) { v ->
            local = local.copy(speed = (v / 10f) + 0.4f); apply()
        })
        box.addView(slider("显示区域 %", 90, (local.displayAreaPercent - 10).coerceIn(0, 90)) { v ->
            local = local.copy(displayAreaPercent = v + 10); apply()
        })
        box.addView(slider("权重过滤", 10, local.weightThreshold.coerceIn(0, 10)) { v ->
            local = local.copy(weightThreshold = v); apply()
        })
        box.addView(row(
            action(if (local.enabled) "关闭弹幕" else "开启弹幕") {
                local = local.copy(enabled = !local.enabled); apply(); refresh()
            },
            action("清空弹幕") { overlay.clearDanmaku(); refresh() }
        ))

        box.addView(sectionTitle("提示"))
        box.addView(TextView(activity).apply {
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 11f
            text = "以上调整只对本次播放生效。若要长期保存（例如 Cookie、匹配阈值、自动加载），" +
                "请打开模块 App「B2Y 弹幕」中的设置页面。"
        })

        box.addView(sectionTitle("诊断（反馈问题时请复制这段）"))
        // 注意：TextView.apply { } 里的 `overlay` 会解析成 View.getOverlay()，
        // 所以必须先在外部取好字符串
        val initialDiagnostics = overlay.diagnostics()
        diagnosticsView = TextView(activity).apply {
            setTextColor(0xFF9FE0A0.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            text = initialDiagnostics
        }
        box.addView(diagnosticsView)
        box.addView(action("复制诊断信息") {
            try {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("B2Y", overlay.diagnostics()))
                overlay.showToast("诊断信息已复制到剪贴板")
            } catch (t: Throwable) {
                Log.w("复制诊断信息失败", t)
            }
        })

        dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("B2Y 弹幕")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("全部重置") { _, _ ->
                local = DanmakuSettings()
                apply()
                refresh()
            }
            .create()
        dialog?.setOnDismissListener { dialog = null }
        dialog?.show()
        refresh()
    }

    fun refresh() {
        val id = VideoSessionController.currentVideoId() ?: "未识别"
        val bvid = VideoSessionController.currentBvid() ?: "-"
        val title = VideoSessionController.currentTitle() ?: "-"
        statusView?.text = buildString {
            append("状态：").append(overlay.currentStatus()).append('\n')
            append("YouTube 视频：").append(id).append('\n')
            append("标题：").append(title).append('\n')
            append("B 站视频：").append(bvid).append('\n')
            append("当前弹幕：").append(overlay.danmakuCount()).append(" 条\n")
            append("播放位置：").append(com.b2y.danmaku.hook.PlaybackClockHolder.clock.positionMs() / 1000)
                .append(" s")
        }
        diagnosticsView?.text = overlay.diagnostics()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    // ------------------------------------------------------------------ 工具

    private fun refreshLocal() {
        local = VideoSessionController.settingsSnapshot().copy(timeOffsetMs = overlay.currentTimeOffsetMs())
    }

    private fun syncFromOverlay() {
        local = local.copy(timeOffsetMs = overlay.currentTimeOffsetMs())
    }

    private fun setOffset(ms: Int) {
        overlay.adjustTimeOffset(ms - overlay.currentTimeOffsetMs())
        syncFromOverlay()
        refresh()
    }

    private fun apply() {
        try {
            overlay.applySettings(local)
            syncFromOverlay()
        } catch (t: Throwable) {
            Log.w("应用临时设置失败", t)
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        setText(text)
        setTextColor(0xFFFB7299.toInt())
        textSize = 13f
        setPadding(0, (14 * activity.resources.displayMetrics.density).toInt(), 0, (6 * activity.resources.displayMetrics.density).toInt())
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (4 * activity.resources.displayMetrics.density).toInt()
            })
        }
    }

    private fun action(text: String, onClick: () -> Unit): Button = Button(activity).apply {
        setText(text)
        textSize = 12f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun slider(label: String, max: Int, progress: Int, onChanged: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply {
            text = "$label：$progress"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val bar = SeekBar(activity).apply {
            this.max = max
            this.progress = progress.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    title.text = "$label：$value"
                    if (fromUser) onChanged(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        wrap.addView(title)
        wrap.addView(bar)
        return wrap
    }

    private fun askText(title: String, hint: String, preset: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            this.hint = hint
            setText(preset)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        try {
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) onOk(text)
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            Log.w("输入弹窗失败", t)
        }
    }
}
