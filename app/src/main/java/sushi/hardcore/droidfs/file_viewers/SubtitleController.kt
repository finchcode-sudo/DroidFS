package sushi.hardcore.droidfs.file_viewers

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import `is`.xyz.mpv.MPVLib
import sushi.hardcore.droidfs.filesystems.EncryptedVolume
import sushi.hardcore.droidfs.util.PathUtils
import java.io.File
import java.util.Locale

/**
 * 字幕功能, 照抄另一个播放器(fam4k007 videoplayer)的字幕功能:
 *  - 字幕入口菜单: 选择字幕轨道 / 导入字幕 / 更多设置
 *  - 打开视频时自动加载同文件夹里同名的字幕 (优先级 ass > srt > ssa > vtt > sub > sbv, 再按系统语言选简/繁)
 *  - 更多设置: 字幕延迟(-60~60秒) / 字幕样式(样式覆盖, 文字颜色, 描边颜色, 背景颜色, 描边粗细, 描边模式)
 *            / 字幕杂项(大小 50%~300%, 垂直位置 0~100) / 字幕字体
 *
 * 字幕文件都在加密卷里, 所以不是复制到手机存储, 而是像视频一样通过 LocalMediaServer
 * 解密后用 127.0.0.1 的 HTTP 地址交给 mpv 的 sub-add, 明文不会落盘。
 */
class SubtitleController(
    private val activity: AppCompatActivity,
    private val volume: () -> EncryptedVolume,
    private val currentPath: () -> String,
    private val prefs: SharedPreferences,
) {
    private val servers = mutableListOf<LocalMediaServer>()
    private var autoPending = false
    private val density = activity.resources.displayMetrics.density

    private fun px(dp: Int) = (dp * density).toInt()

    // ---------------------------------------------------------------- 生命周期

    fun stopServers() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    /** 每次开始播放一个新视频时调用 */
    fun onNewFile() {
        stopServers()
        autoPending = true
        // mpv 的 sub-delay 在切换文件时不会自动回到 0
        MPVLib.setPropertyDouble("sub-delay", 0.0)
    }

    /** 每 500ms 调用一次, 视频的轨道信息出来后自动加载同名字幕 */
    fun onTick() {
        if (autoPending && (MPVLib.getPropertyInt("track-list/count") ?: 0) > 0) {
            autoPending = false
            autoLoad()
        }
    }

    /** 播放器初始化后恢复上次保存的字幕样式 */
    fun applySaved() {
        fun str(key: String, prop: String) {
            prefs.getString(key, null)?.let { MPVLib.setOptionString(prop, it) }
        }
        str("sub_color", "sub-color")
        str("sub_border_color", "sub-border-color")
        str("sub_back_color", "sub-back-color")
        str("sub_border_size", "sub-border-size")
        str("sub_border_style", "sub-border-style")
        str("sub_scale", "sub-scale")
        str("sub_pos", "sub-pos")
        str("sub_font", "sub-font")
        str("sub_override", "sub-ass-override")
    }

    // ---------------------------------------------------------------- 入口菜单

    fun showMenu() {
        AlertDialog.Builder(activity)
            .setTitle("字幕")
            .setItems(arrayOf("选择字幕轨道", "导入字幕", "更多设置")) { _, which ->
                when (which) {
                    0 -> showTrackDialog()
                    1 -> showImportDialog()
                    2 -> showSettingsMenu()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 选择字幕轨道

    private data class SubTrack(val id: Int, val name: String)

    private fun loadTracks(): List<SubTrack> {
        val list = arrayListOf(SubTrack(-1, "关闭字幕"))
        val count = MPVLib.getPropertyInt("track-list/count") ?: return list
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val lang = MPVLib.getPropertyString("track-list/$i/lang")
            val title = MPVLib.getPropertyString("track-list/$i/title")
            val external = MPVLib.getPropertyBoolean("track-list/$i/external") == true
            var name = when {
                !lang.isNullOrEmpty() && !title.isNullOrEmpty() -> "#$id: $title ($lang)"
                !lang.isNullOrEmpty() || !title.isNullOrEmpty() -> "#$id: ${lang ?: ""}${title ?: ""}"
                else -> "#$id"
            }
            if (external) name += " [外挂]"
            list.add(SubTrack(id, name))
        }
        return list
    }

    private fun showTrackDialog() {
        val tracks = loadTracks()
        val selected = MPVLib.getPropertyInt("sid") ?: -1
        val checked = tracks.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("选择字幕轨道")
            .setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), checked) { dialog, which ->
                val t = tracks[which]
                if (t.id == -1) {
                    MPVLib.setPropertyString("sid", "no")
                } else {
                    MPVLib.setPropertyInt("sid", t.id)
                    MPVLib.setPropertyBoolean("sub-visibility", true)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 导入 / 自动加载

    private val priority = listOf("ass", "srt", "ssa", "vtt", "sub", "sbv")
    private val importable = setOf("srt", "ass", "ssa", "vtt", "sub", "sbv", "lrc", "smi", "sup")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun langPriority(after: String, sc: Boolean, tc: Boolean): Int = when {
        sc && listOf("sc", "chs", "简", "zh-cn").any { after.contains(it) } -> 0
        tc && listOf("tc", "cht", "繁", "zh-tw").any { after.contains(it) } -> 0
        else -> 1
    }

    /** 和原播放器一样的排序: 扩展名优先级 → 是否和视频完全同名 → 语言 → 名字短的 → 字母序 */
    private fun comparator(videoName: String): Comparator<String> {
        val lang = Locale.getDefault().toString().lowercase()
        val sc = lang.contains("zh_cn") || lang.contains("zh_hans")
        val tc = lang.contains("zh_tw") || lang.contains("zh_hk") || lang.contains("zh_hant")
        val v = videoName.lowercase()
        return compareBy<String>(
            { priority.indexOf(ext(it)).let { i -> if (i == -1) 999 else i } },
            { if (it.substringBeforeLast('.').equals(videoName, ignoreCase = true)) 0 else 1 },
            { langPriority(it.substringBeforeLast('.').lowercase().removePrefix(v), sc, tc) },
            { it.substringBeforeLast('.').length },
            { it.lowercase() },
        )
    }

    private fun listSubtitles(onlySameName: Boolean, callback: (List<Pair<String, String>>) -> Unit) {
        val path = currentPath()
        val dir = PathUtils.getParentPath(path)
        val videoName = File(path).nameWithoutExtension
        Thread {
            val result = try {
                val names = volume().readDir(dir)
                    ?.filter { it.isRegularFile }
                    ?.filter {
                        if (onlySameName) {
                            ext(it.name) in priority && it.name.lowercase().startsWith(videoName.lowercase())
                        } else {
                            ext(it.name) in importable
                        }
                    }
                    ?.associate { it.name to it.fullPath }
                    ?: emptyMap()
                names.keys.sortedWith(comparator(videoName)).map { it to names.getValue(it) }
            } catch (e: Throwable) {
                emptyList()
            }
            activity.runOnUiThread { callback(result) }
        }.start()
    }

    private fun autoLoad() {
        listSubtitles(true) { subs ->
            subs.firstOrNull()?.let { (name, fullPath) ->
                addExternal(fullPath, name, true)
            }
        }
    }

    private fun showImportDialog() {
        listSubtitles(false) { subs ->
            if (subs.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle("导入字幕")
                    .setMessage("当前视频所在的文件夹里没有字幕文件 (srt / ass / ssa / vtt / sub / sbv ...)。\n请先把字幕文件导入到和视频相同的文件夹。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("导入字幕")
                    .setItems(subs.map { it.first }.toTypedArray()) { _, which ->
                        addExternal(subs[which].second, subs[which].first, false)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun addExternal(fullPath: String, name: String, auto: Boolean) {
        val server = LocalMediaServer(volume(), fullPath)
        servers.add(server)
        Thread {
            try {
                // 服务器不看请求路径, 把扩展名放进 URL 帮 mpv 判断字幕格式
                val url = server.start().removeSuffix("video") + "sub." + ext(name)
                activity.runOnUiThread {
                    MPVLib.command(arrayOf("sub-add", url, "select", name))
                    MPVLib.setPropertyBoolean("sub-visibility", true)
                    Toast.makeText(activity, (if (auto) "已自动加载字幕: " else "已导入字幕: ") + name, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "字幕加载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 更多设置

    private fun showSettingsMenu() {
        AlertDialog.Builder(activity)
            .setTitle("更多设置")
            .setItems(arrayOf("字幕延迟设置", "字幕样式设置", "字幕杂项设置 (大小 / 位置)", "字幕字体设置")) { _, which ->
                when (which) {
                    0 -> showDelayDialog()
                    1 -> showStyleDialog()
                    2 -> showMiscDialog()
                    3 -> showFontDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun vbox(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(8), px(20), px(8))
    }

    private fun label(text: String, size: Float = 14f): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setPadding(0, px(10), 0, px(4))
    }

    private fun hint(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        alpha = 0.7f
    }

    private fun saveProp(key: String, prop: String, value: String) {
        MPVLib.setPropertyString(prop, value)
        prefs.edit().putString(key, value).apply()
    }

    // ---- 延迟

    private fun showDelayDialog() {
        var delay = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
        val root = vbox()
        val edit = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.CENTER
        }
        fun set(v: Double) {
            delay = v.coerceIn(-60.0, 60.0)
            edit.setText(String.format(Locale.US, "%.2f", delay))
            MPVLib.setPropertyDouble("sub-delay", delay)
        }
        edit.setText(String.format(Locale.US, "%.2f", delay))
        root.addView(label("当前延迟 (秒), 正数延后, 负数提前"))
        root.addView(edit)
        root.addView(hint("范围: -60.0 ~ +60.0 秒"))
        fun row(vararg items: Pair<String, Double>) {
            val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for ((text, d) in items) {
                r.addView(Button(activity).apply {
                    this.text = text
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { set((edit.text.toString().toDoubleOrNull() ?: delay) + d) }
                })
            }
            root.addView(r)
        }
        row("-0.5s" to -0.5, "+0.5s" to 0.5)
        row("-0.1s" to -0.1, "+0.1s" to 0.1)
        root.addView(Button(activity).apply {
            text = "重置为 0"
            setOnClickListener { set(0.0) }
        })
        AlertDialog.Builder(activity)
            .setTitle("字幕延迟设置")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                edit.text.toString().toDoubleOrNull()?.let { set(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 样式

    private fun colorSwatches(
        title: String, key: String, prop: String, colors: List<Pair<String, String>>, defaultColor: String,
    ): LinearLayout {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val current = prefs.getString(key, null) ?: defaultColor
        val currentView = label("$title (当前 $current)")
        box.addView(currentView)
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, hex) in colors) {
            strip.addView(TextView(activity).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (hex.equals("#FFFFFF", true) || hex.equals("#FFFF00", true) || hex.equals("#00FFFF", true) || hex.equals("#00FF00", true)) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (hex.length == 7) hex else hex))
                    setStroke(px(1), Color.GRAY)
                    cornerRadius = px(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(px(52), px(40)).apply { rightMargin = px(6) }
                setOnClickListener {
                    saveProp(key, prop, hex)
                    currentView.text = "$title (当前 $hex)"
                }
            })
        }
        box.addView(HorizontalScrollView(activity).apply { addView(strip) })
        val custom = EditText(activity).apply {
            hint = "自定义: #RRGGBB 或 #AARRGGBB"
            textSize = 13f
            setSingleLine()
        }
        box.addView(custom)
        box.addView(Button(activity).apply {
            text = "应用自定义颜色"
            setOnClickListener {
                val v = custom.text.toString().trim()
                try {
                    Color.parseColor(v)
                    saveProp(key, prop, v)
                    currentView.text = "$title (当前 $v)"
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(activity, "颜色格式不对", Toast.LENGTH_SHORT).show()
                }
            }
        })
        return box
    }

    private fun showStyleDialog() {
        val root = vbox()

        // 样式覆盖
        val overrideOn = (prefs.getString("sub_override", null) ?: MPVLib.getPropertyString("sub-ass-override")) == "force"
        root.addView(Switch(activity).apply {
            text = "样式覆盖"
            isChecked = overrideOn
            setOnCheckedChangeListener { _, enabled ->
                saveProp("sub_override", "sub-ass-override", if (enabled) "force" else "scale")
                MPVLib.command(arrayOf("sub-reload"))
            }
        })
        root.addView(hint("关闭时使用字幕文件内嵌样式。内嵌 ASS 字幕需开启样式覆盖才能应用自定义样式"))

        root.addView(colorSwatches("字幕颜色", "sub_color", "sub-color", listOf(
            "白" to "#FFFFFF", "黄" to "#FFFF00", "青" to "#00FFFF", "绿" to "#00FF00",
            "红" to "#FF0000", "黑" to "#000000",
        ), "#FFFFFF"))

        root.addView(colorSwatches("描边颜色", "sub_border_color", "sub-border-color", listOf(
            "黑" to "#000000", "白" to "#FFFFFF", "红" to "#FF0000", "蓝" to "#0000FF", "灰" to "#808080",
        ), "#000000"))

        root.addView(colorSwatches("背景颜色", "sub_back_color", "sub-back-color", listOf(
            "透明" to "#00000000", "黑" to "#FF000000", "白" to "#FFFFFFFF",
            "红50%" to "#80FF0000", "绿50%" to "#8000FF00", "蓝50%" to "#800000FF",
            "黄50%" to "#80FFFF00", "灰50%" to "#80808080",
        ), "#00000000"))

        // 描边粗细 0~10
        val size = (prefs.getString("sub_border_size", null)?.toDoubleOrNull()
            ?: MPVLib.getPropertyDouble("sub-border-size") ?: 3.0).toInt().coerceIn(0, 10)
        val sizeLabel = label("描边粗细: $size")
        val sizeBar = SeekBar(activity).apply {
            max = 10
            progress = size
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sizeLabel.text = "描边粗细: $p"
                    if (fromUser) saveProp("sub_border_size", "sub-border-size", p.toString())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(sizeLabel)
        root.addView(sizeBar)
        root.addView(Button(activity).apply {
            text = "重置为 3"
            setOnClickListener {
                sizeBar.progress = 3
                saveProp("sub_border_size", "sub-border-size", "3")
            }
        })

        // 描边模式
        root.addView(label("描边模式"))
        val styles = listOf(
            "模式A (通过描边颜色项修改)" to "outline-and-shadow",
            "模式B (通过描边颜色项修改)" to "opaque-box",
            "模式C (通过背景颜色项修改)" to "background-box",
        )
        val curStyle = prefs.getString("sub_border_style", null) ?: MPVLib.getPropertyString("sub-border-style") ?: "outline-and-shadow"
        val group = RadioGroup(activity)
        styles.forEachIndexed { i, (text, value) ->
            group.addView(RadioButton(activity).apply {
                this.text = text
                id = View.generateViewId()
                isChecked = value == curStyle
                setOnClickListener { saveProp("sub_border_style", "sub-border-style", value) }
            })
        }
        root.addView(group)

        AlertDialog.Builder(activity)
            .setTitle("字幕样式设置")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- 杂项: 大小 / 位置

    private fun showMiscDialog() {
        val root = vbox()
        var scale = ((prefs.getString("sub_scale", null)?.toDoubleOrNull()
            ?: MPVLib.getPropertyDouble("sub-scale") ?: 1.0) * 100).toInt().coerceIn(50, 300)
        var pos = (prefs.getString("sub_pos", null)?.toIntOrNull()
            ?: MPVLib.getPropertyInt("sub-pos") ?: 100).coerceIn(0, 100)

        val scaleLabel = label("字幕大小: $scale%")
        val scaleBar = SeekBar(activity).apply {
            max = 250
            progress = scale - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    scale = p + 50
                    scaleLabel.text = "字幕大小: $scale%"
                    if (fromUser) saveProp("sub_scale", "sub-scale", String.format(Locale.US, "%.2f", scale / 100.0))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(scaleLabel)
        root.addView(scaleBar)
        root.addView(hint("范围: 50% ~ 300%"))

        val posLabel = label("字幕垂直位置: $pos")
        val posBar = SeekBar(activity).apply {
            max = 100
            progress = pos
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    pos = p
                    posLabel.text = "字幕垂直位置: $pos"
                    if (fromUser) saveProp("sub_pos", "sub-pos", p.toString())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(posLabel)
        root.addView(posBar)
        root.addView(hint("范围: 0 (顶部) ~ 100 (底部)"))

        root.addView(Button(activity).apply {
            text = "重置默认值 (100%, 位置100)"
            setOnClickListener {
                scaleBar.progress = 50
                posBar.progress = 100
                saveProp("sub_scale", "sub-scale", "1.00")
                saveProp("sub_pos", "sub-pos", "100")
            }
        })

        AlertDialog.Builder(activity)
            .setTitle("字幕杂项设置")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- 字体

    private fun showFontDialog() {
        val root = vbox()
        val current = prefs.getString("sub_font", null).orEmpty()
        root.addView(label("当前字体: ${current.ifBlank { "默认字体" }}"))
        val edit = EditText(activity).apply {
            hint = "字体名称, 例如 Noto Sans CJK SC"
            setText(current)
            setSingleLine()
        }
        root.addView(edit)
        root.addView(hint("字体名称要和手机里已有的字体一致; 留空 / 点\"默认字体\"使用默认。\n字体更改需要重新播放视频才能生效。内嵌 ASS 字幕需开启样式覆盖后字体设置才会生效。"))
        AlertDialog.Builder(activity)
            .setTitle("字幕字体设置")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) {
                    prefs.edit().remove("sub_font").apply()
                    MPVLib.setPropertyString("sub-font", "sans-serif")
                } else {
                    saveProp("sub_font", "sub-font", name)
                }
            }
            .setNeutralButton("默认字体") { _, _ ->
                prefs.edit().remove("sub_font").apply()
                MPVLib.setPropertyString("sub-font", "sans-serif")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
