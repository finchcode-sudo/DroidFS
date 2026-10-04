package sushi.hardcore.droidfs.file_viewers

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.launch
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.databinding.ActivityVideoPlayerBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * mpv 版视频播放器。
 *
 * 之前用 ExoPlayer 播放裸 TS 文件时, 因为没有索引, ExoPlayer 读不出总时长也不能拖动进度条。
 * mpv(基于 FFmpeg 的解封装器) 能正确处理这类文件, 但它是原生进程, 不能像 ExoPlayer 那样直接
 * 接一个 Kotlin DataSource。这里用 LocalMediaServer 在 127.0.0.1 起一个只有本机能访问的
 * HTTP 服务, 把加密卷解密出来的数据用支持 Range 的方式喂给 mpv, 跟播放一个普通的、支持
 * 拖动的在线视频链接是一样的效果。
 *
 * 注意: 这是本次改动里验证程度最低的一部分, 没有实际编译/真机跑过, 需要跟着 CI 报错再修。
 */
class VideoPlayer : FileViewerActivity() {
    override val blackBackground: Boolean = true

    private lateinit var binding: ActivityVideoPlayerBinding
    private var mediaServer: LocalMediaServer? = null
    private var isPlaying = true
    private var isUserSeeking = false
    private var firstPlay = true
    private val autoFit by lazy { sharedPrefs.getBoolean("autoFit", false) }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            progressHandler.postDelayed(this, 500)
        }
    }

    // 划屏快进/快退
    private var swipeSeekStartX = 0f
    private var swipeSeekStartY = 0f
    private var swipeSeekStartPositionSec = 0.0
    private var isSwipeSeeking = false
    // 和 mpv-android 官方一致: 从屏幕左边滑到右边 = 150 秒 (TouchGestures.CONTROL_SEEK_MAX)
    private val swipeSeekFullWidthSec = 150.0
    // 和官方一致: 触发滑动需要移动 屏幕宽高较小值的 1/30 (TouchGestures.TRIGGER_RATE)
    private val swipeSeekThresholdPx by lazy {
        minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 30f
    }

    // 长按加速
    private var isLongPressSpeeding = false
    private val longPressSpeedMultiplier = 2.0
    private val longPressTimeoutMs = 350L
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    override fun getFileType(): String = "video"

    override fun viewFile() {
        try {
            viewFileInner()
        } catch (e: Throwable) {
            showCrashDialog(e)
        }
    }

    private fun showCrashDialog(e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = sw.toString()
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            textSize = 12f
        }
        scrollView.addView(textView)
        android.app.AlertDialog.Builder(this)
            .setTitle("视频播放器出错")
            .setView(scrollView)
            .setPositiveButton("关闭") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun viewFileInner() {
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rotateButton.setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
        }

        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.prevButton.setOnClickListener {
            lifecycleScope.launch {
                playlistNext(false)
                loadCurrentFile()
            }
        }
        binding.nextButton.setOnClickListener {
            lifecycleScope.launch {
                playlistNext(true)
                loadCurrentFile()
            }
        }
        binding.rewindButton.setOnClickListener {
            MPVLib.command(arrayOf("no-osd", "seek", "-10", "relative"))
        }
        binding.forwardButton.setOnClickListener {
            MPVLib.command(arrayOf("no-osd", "seek", "10", "relative"))
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                if (duration > 0) {
                    val target = duration * (seekBar!!.progress / 1000.0)
                    MPVLib.command(arrayOf("no-osd", "seek", target.toString(), "absolute"))
                }
                isUserSeeking = false
            }
        })

        setupGestures()

        lifecycleScope.launch {
            createPlaylist()
        }

        binding.videoPlayer.initialize(filesDir.path, cacheDir.path)
        loadCurrentFile()
        progressHandler.post(progressRunnable)
    }

    private fun loadCurrentFile() {
        mediaServer?.stop()
        val path = fileViewerViewModel.filePath!!
        val server = LocalMediaServer(encryptedVolume, path)
        mediaServer = server
        binding.playPauseButton.setImageResource(R.drawable.exo_icon_pause)
        binding.textFileName.text = java.io.File(path).name
        // ServerSocket 是网络操作, 安卓不允许在主线程上做, 必须放到后台线程,
        // 起好之后再切回主线程去真正加载和播放
        Thread {
            try {
                val url = server.start()
                runOnUiThread {
                    binding.videoPlayer.loadUrl(url)
                    MPVLib.setPropertyBoolean("pause", false)
                    isPlaying = true
                    firstPlay = true
                }
            } catch (e: Throwable) {
                runOnUiThread { showCrashDialog(e) }
            }
        }.start()
    }

    private fun togglePlayPause() {
        isPlaying = !isPlaying
        MPVLib.setPropertyBoolean("pause", !isPlaying)
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.exo_icon_pause else R.drawable.exo_icon_play
        )
    }

    private fun formatTime(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "00:00"
        val totalSec = seconds.roundToInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun updateProgressUi() {
        val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0

        if (!isUserSeeking) {
            binding.textPosition.text = formatTime(position)
            binding.textDuration.text = formatTime(duration)
            if (duration > 0) {
                binding.seekBar.progress = ((position / duration) * 1000).toInt().coerceIn(0, 1000)
            }
        }

        // mpv 用的是 keep-open, 播完不会自动跳下一个, 这里手动检测接近结尾就切下一个
        if (duration > 0 && position >= duration - 0.5 && isPlaying) {
            lifecycleScope.launch {
                playlistNext(true)
                loadCurrentFile()
            }
        }

        if (firstPlay && autoFit) {
            val w = MPVLib.getPropertyInt("video-params/w") ?: 0
            val h = MPVLib.getPropertyInt("video-params/h") ?: 0
            if (w > 0 && h > 0) {
                requestedOrientation = if (w < h)
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                firstPlay = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        binding.videoPlayer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeSeekStartX = event.x
                    swipeSeekStartY = event.y
                    swipeSeekStartPositionSec = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                    isSwipeSeeking = false
                    isLongPressSpeeding = false

                    longPressRunnable = Runnable {
                        if (!isSwipeSeeking) {
                            isLongPressSpeeding = true
                            MPVLib.setPropertyDouble("speed", longPressSpeedMultiplier)
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, longPressTimeoutMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - swipeSeekStartX
                    val dy = event.y - swipeSeekStartY
                    if (!isSwipeSeeking && abs(dx) > swipeSeekThresholdPx && abs(dx) > abs(dy)) {
                        isSwipeSeeking = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        toggleControlsVisibility(true)
                    }
                    if (isSwipeSeeking) {
                        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                        if (duration > 0) {
                            val deltaSec = (dx / view.width) * swipeSeekFullWidthSec
                            val target = (swipeSeekStartPositionSec + deltaSec).coerceIn(0.0, duration)
                            MPVLib.command(arrayOf("no-osd", "seek", target.toString(), "absolute+keyframes"))
                            // 和 mpv-android 一样, 屏幕中间显示 当前位置 和 [相对起点的变化量]
                            val diffSec = (target - swipeSeekStartPositionSec).roundToInt()
                            binding.gestureText.text = "${prettyTime(target.roundToInt())}\n[${prettyTime(diffSec, true)}]"
                            binding.gestureText.visibility = View.VISIBLE
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    binding.gestureText.visibility = View.GONE
                    val wasSeeking = isSwipeSeeking
                    val wasSpeeding = isLongPressSpeeding
                    if (wasSpeeding) {
                        MPVLib.setPropertyDouble("speed", 1.0)
                    }
                    if (!wasSeeking && !wasSpeeding) {
                        // 单击: 切换控制栏显示/隐藏
                        val visible = binding.bottomBar.visibility == View.VISIBLE
                        toggleControlsVisibility(!visible)
                    }
                    isSwipeSeeking = false
                    isLongPressSpeeding = false
                    wasSeeking || wasSpeeding
                }
                else -> false
            }
        }
    }

    private fun prettyTime(d: Int, sign: Boolean = false): String {
        if (sign) return (if (d >= 0) "+" else "-") + prettyTime(abs(d))
        val hours = d / 3600
        val minutes = d % 3600 / 60
        val seconds = d % 60
        return if (hours == 0) "%02d:%02d".format(minutes, seconds) else "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun toggleControlsVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.topBar.visibility = v
        binding.bottomBar.visibility = v
        if (visible) showPartialSystemUi() else hideSystemUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        mediaServer?.stop()
        binding.videoPlayer.destroy()
    }
}
