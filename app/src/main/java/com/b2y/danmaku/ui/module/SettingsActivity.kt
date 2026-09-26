package com.b2y.danmaku.ui.module

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.b2y.danmaku.core.DanmakuSettings
import com.b2y.danmaku.core.Settings

/**
 * 模块自身的设置界面（模块 App 的启动页）。
 *
 * 设置以 JSON 形式写入模块的 SharedPreferences，被注入的 YouTube 进程通过 XSharedPreferences 读取。
 */
class SettingsActivity : Activity() {

    private lateinit var box: LinearLayout
    private var current: DanmakuSettings = DanmakuSettings()

    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = Settings.loadLocal(this)
        // Shorts 开关以「被注入进程自己维护的运行时值」为准（如果设置过），
        // 这样设置页显示的状态和 YouTube 里实际生效的完全一致
        current = current.copy(
            matchInShorts = Settings.loadLocalMatchInShorts(this, current.matchInShorts)
        )

        val scroll = ScrollView(this)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(box)
        setContentView(scroll)

        buildUi()
    }

    private fun buildUi() {
        box.removeAllViews()

        box.addView(header("B2Y 弹幕 · 设置  v${com.b2y.danmaku.BuildConfig.VERSION_NAME}"))
        box.addView(header("使用说明"))
        box.addView(note(
            "1. 在 Vector / LSPosed 中启用本模块，并把「YouTube」加入作用域（本模块已在清单中声明作用域）。\n" +
                "2. 强制停止并重新打开 YouTube 客户端。\n" +
                "3. 打开任意视频，弹幕会自动按标题匹配 B 站视频并加载。\n" +
                "4. 播放页右下角悬浮「弹」按钮可以临时调整显示效果、重新搜索或手动粘贴 B 站链接。\n\n" +
                "保存后的设置在重新打开视频（或切换视频）后生效。"
        ))

        box.addView(header("基础"))
        box.addView(check("启用弹幕", current.enabled) { current = current.copy(enabled = it) })
        box.addView(check("进入视频后自动搜索并加载", current.autoLoad) { current = current.copy(autoLoad = it) })
        box.addView(check("显示悬浮「弹」按钮", current.showFloatButton) { current = current.copy(showFloatButton = it) })
        box.addView(check("优先使用 YouTube 原始标题（oEmbed，匹配更准）", current.preferOembedTitle) {
            current = current.copy(preferOembedTitle = it)
        })

        box.addView(header("Shorts（竖屏短视频）"))
        box.addView(check("在 Shorts 里也匹配并显示弹幕", current.matchInShorts) {
            current = current.copy(matchInShorts = it)
        })
        box.addView(note(
            "不勾选（默认）：识别到 Shorts 时，不搜索、不显示弹幕，悬浮「弹」按钮也隐藏。\n" +
                "勾选：Shorts 照常按标题匹配 B 站视频，行为和 1.0.0 一样。\n\n" +
                "这里改完要点下面的「保存设置」，然后在 YouTube 里切换一次视频生效。\n" +
                "想立刻生效：播放页悬浮「弹」按钮 → 面板最上方的同名开关（立即生效，无需重启）。"
        ))

        box.addView(header("弹幕显示"))
        box.addView(slider("不透明度 %", 20, 100, current.opacity) { current = current.copy(opacity = it) })
        box.addView(slider("字号 (sp)", 8, 36, current.fontSizeSp.toInt()) {
            current = current.copy(fontSizeSp = it.toFloat())
        })
        box.addView(slider("滚动速度 ×100", 40, 250, (current.speed * 100).toInt()) {
            current = current.copy(speed = it / 100f)
        })
        box.addView(slider("轨道间距 (dp)", 0, 24, current.trackSpacingDp) {
            current = current.copy(trackSpacingDp = it)
        })
        box.addView(slider("显示区域高度 %", 10, 100, current.displayAreaPercent) {
            current = current.copy(displayAreaPercent = it)
        })
        box.addView(slider("权重过滤（低于该值不显示，0=不过滤）", 0, 10, current.weightThreshold) {
            current = current.copy(weightThreshold = it)
        })
        box.addView(slider("时间轴偏移 (ms，正数=弹幕提前)", -2000, 2000, current.timeOffsetMs) {
            current = current.copy(timeOffsetMs = it)
        })
        box.addView(check("显示顶部弹幕", current.showTop) { current = current.copy(showTop = it) })
        box.addView(check("显示底部弹幕", current.showBottom) { current = current.copy(showBottom = it) })

        box.addView(header("匹配"))
        box.addView(slider("标题匹配阈值 %", 50, 100, current.matchThreshold) {
            current = current.copy(matchThreshold = it)
        })
        box.addView(choice("多结果处理", listOf("mostDanmaku" to "自动选弹幕最多", "ask" to "弹窗让我选择"), current.multiMatchMode) {
            current = current.copy(multiMatchMode = it)
        })
        box.addView(slider("单视频最大弹幕条数", 1000, 20000, current.maxDanmakuCount) {
            current = current.copy(maxDanmakuCount = it)
        })

        box.addView(header("B 站账号与手动指定"))
        box.addView(textInput("SESSDATA（可选，登录后可提高接口成功率）", current.sessData, singleLine = true) {
            current = current.copy(sessData = it)
        })
        box.addView(textInput("强制指定 B 站视频（bvid / av 号 / 链接，留空则自动匹配）", current.manualBvid, singleLine = true) {
            current = current.copy(manualBvid = it)
        })

        box.addView(header(""))
        val save = Button(this).apply {
            text = "保存设置"
            isAllCaps = false
            setOnClickListener {
                Settings.saveLocal(this@SettingsActivity, current)
                // Shorts 开关额外写一份被注入进程一定会读到的副本
                Settings.saveLocalMatchInShorts(this@SettingsActivity, current.matchInShorts)
                Toast.makeText(
                    this@SettingsActivity,
                    "已保存。请在 YouTube 中切换一次视频。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        box.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ 控件

    private fun header(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(0xFFFB7299.toInt())
        textSize = 15f
        setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
    }

    private fun note(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(0xFFCCCCCC.toInt())
        textSize = 12f
        setBackgroundColor(0x22FFFFFF)
        val p = (10 * density).toInt()
        setPadding(p, p, p, p)
    }

    private fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun slider(label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "$label：$value"
        }
        val bar = SeekBar(this).apply {
            this.max = (max - min)
            progress = (value - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val real = progress + min
                    title.text = "$label：$real"
                    onChange(real)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        wrap.addView(title)
        wrap.addView(bar)
        return wrap
    }

    private fun choice(label: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        for ((key, text) in options) {
            wrap.addView(android.widget.RadioButton(this).apply {
                this.text = text
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 13f
                isChecked = key == value
                setOnClickListener { onChange(key); buildUi() }
            })
        }
        return wrap
    }

    private fun textInput(label: String, value: String, singleLine: Boolean, onChange: (String) -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })
        wrap.addView(EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(singleLine)
            setTextColor(Color.WHITE)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
            })
        })
        return wrap
    }
}
