package sushi.hardcore.droidfs.file_viewers

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.databinding.ActivityVideoPlayerBinding

class VideoPlayer: MediaPlayer(true) {
    private var firstPlay = true
    private val autoFit by lazy {
        sharedPrefs.getBoolean("autoFit", false)
    }
    private lateinit var binding: ActivityVideoPlayerBinding

    // 划屏快进/快退
    private var swipeSeekStartX = 0f
    private var swipeSeekStartY = 0f
    private var swipeSeekStartPositionMs = 0L
    private var isSwipeSeeking = false
    private val swipeSeekThresholdPx by lazy { resources.displayMetrics.density * 24 }

    // 长按加速
    private var isLongPressSpeeding = false
    private var originalPlaybackSpeed = 1f
    private val longPressSpeedMultiplier = 2f
    private val longPressTimeoutMs = 350L
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    override fun viewFile() {
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.fitsSystemWindows = true
        binding.videoPlayer.doubleTapOverlay = binding.doubleTapOverlay
        val bottomBar = findViewById<FrameLayout>(R.id.exo_bottom_bar)
        val progressBar = findViewById<View>(R.id.exo_progress)
        ViewCompat.setOnApplyWindowInsetsListener(binding.videoPlayer) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomBar.apply {
                updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
                updateLayoutParams<FrameLayout.LayoutParams> {
                    @SuppressLint("PrivateResource")
                    height = resources.getDimensionPixelSize(R.dimen.exo_styled_bottom_bar_height) + insets.bottom
                }
            }
            progressBar.apply {
                updatePadding(left = insets.left, right = insets.right)
                updateLayoutParams<FrameLayout.LayoutParams> {
                    @SuppressLint("PrivateResource")
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.exo_styled_progress_margin_bottom) + insets.bottom
                }
            }
            windowInsets
        }

        binding.videoPlayer.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            binding.topBar.visibility = visibility
            if (visibility == View.VISIBLE) {
                showPartialSystemUi()
            } else {
                hideSystemUi()
            }
        })
        binding.rotateButton.setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
        }
        setupSwipeSeek()
        super.viewFile()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeSeek() {
        binding.videoPlayer.setOnTouchListener { view, event ->
            val player = binding.videoPlayer.player
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeSeekStartX = event.x
                    swipeSeekStartY = event.y
                    swipeSeekStartPositionMs = player?.currentPosition ?: 0L
                    isSwipeSeeking = false
                    isLongPressSpeeding = false

                    longPressRunnable = Runnable {
                        if (!isSwipeSeeking && player != null) {
                            isLongPressSpeeding = true
                            originalPlaybackSpeed = player.playbackParameters.speed
                            player.setPlaybackSpeed(originalPlaybackSpeed * longPressSpeedMultiplier)
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, longPressTimeoutMs)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - swipeSeekStartX
                    val dy = event.y - swipeSeekStartY
                    if (!isSwipeSeeking &&
                        abs(dx) > swipeSeekThresholdPx &&
                        abs(dx) > abs(dy)
                    ) {
                        isSwipeSeeking = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        binding.videoPlayer.showController()
                    }
                    if (isSwipeSeeking && player != null) {
                        val duration = player.duration
                        if (duration > 0) {
                            val deltaMs = (dx / view.width * duration).toLong()
                            val target = (swipeSeekStartPositionMs + deltaMs).coerceIn(0, duration)
                            player.seekTo(target)
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    val wasSeeking = isSwipeSeeking
                    val wasSpeeding = isLongPressSpeeding
                    if (wasSpeeding && player != null) {
                        player.setPlaybackSpeed(originalPlaybackSpeed)
                    }
                    isSwipeSeeking = false
                    isLongPressSpeeding = false
                    wasSeeking || wasSpeeding
                }
                else -> false
            }
        }
    }

    override fun bindPlayer(player: ExoPlayer) {
        binding.videoPlayer.player = player
    }

    override fun onNewFileName(fileName: String) {
        binding.textFileName.text = fileName
    }

    override fun getFileType(): String {
        return "video"
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (firstPlay && autoFit) {
            requestedOrientation = if (width < height)
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            firstPlay = false
        }
    }
}
