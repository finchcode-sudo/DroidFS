package sushi.hardcore.droidfs.file_viewers

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.isGone
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.ImageLoader
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.launch
import sushi.hardcore.droidfs.Constants
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.databinding.ActivityImageViewerBinding
import sushi.hardcore.droidfs.filesystems.EncryptedFileReaderFileSystem
import sushi.hardcore.droidfs.widgets.CustomAlertDialogBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ImageViewer: FileViewerActivity(true) {
    companion object {
        private const val hideDelay: Long = 3000
    }

    class ImageViewModel : ViewModel() {
        var imageLoader: ImageLoader? = null
        val rotationAngles = mutableMapOf<String, Float>()
    }

    private lateinit var handler: Handler
    private val imageViewModel: ImageViewModel by viewModels()
    private lateinit var pagerAdapter: ImageViewerPagerAdapter
    private var slideshowActive = false
    private val hideUI = Runnable {
        binding.overlay.visibility = View.GONE
        hideSystemUi()
    }
    private val slideshowNext = Runnable {
        if (slideshowActive) {
            goToPage(1)
        }
    }
    private lateinit var binding: ActivityImageViewerBinding

    override fun getFileType(): String {
        return "image"
    }

    override fun viewFile() {
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.overlay.fitsSystemWindows = true
        if (imageViewModel.imageLoader == null) {
            imageViewModel.imageLoader = ImageLoader.Builder(this).diskCache(null)
                .fileSystem(EncryptedFileReaderFileSystem(encryptedVolume)).build()
        }
        handler = Handler(mainLooper)

        binding.imageDelete.setOnClickListener {
            CustomAlertDialogBuilder(this, theme)
                .keepFullScreen()
                .setTitle(R.string.warning)
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        val deletedIndex = fileViewerViewModel.currentPlaylistIndex
                        if (deleteCurrentFile()) {
                            if (fileViewerViewModel.playlist.isEmpty()) {
                                goBackToExplorer()
                            } else {
                                pagerAdapter.notifyItemRemoved(deletedIndex)
                                binding.imageViewerPager.setCurrentItem(fileViewerViewModel.currentPlaylistIndex, false)
                                updateFileName()
                            }
                        } else {
                            CustomAlertDialogBuilder(this@ImageViewer, theme)
                                .keepFullScreen()
                                .setTitle(R.string.error)
                                .setMessage(getString(R.string.remove_failed, File(fileViewerViewModel.filePath!!).name))
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .setMessage(getString(R.string.single_delete_confirm, File(fileViewerViewModel.filePath!!).name))
                .show()
        }
        binding.imageButtonSlideshow.setOnClickListener {
            if (!slideshowActive) {
                slideshowActive = true
                handler.postDelayed(slideshowNext, Constants.SLIDESHOW_DELAY)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                hideUI.run()
                Toast.makeText(this, R.string.slideshow_started, Toast.LENGTH_SHORT).show()
            } else {
                stopSlideshow()
            }
        }
        binding.imagePrevious.setOnClickListener {
            askSaveRotation { goToPage(-1) }
        }
        binding.imageNext.setOnClickListener {
            askSaveRotation { goToPage(1) }
        }
        binding.imageRotateRight.setOnClickListener { onClickRotate(90f) }
        binding.imageRotateLeft.setOnClickListener { onClickRotate(-90f) }
        onBackPressedDispatcher.addCallback(this) {
            if (slideshowActive) {
                stopSlideshow()
            } else {
                askSaveRotation {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        lifecycleScope.launch {
            createPlaylist()
            setupPager()
        }

        handler.postDelayed(hideUI, hideDelay)
    }

    private fun setupPager() {
        pagerAdapter = ImageViewerPagerAdapter(
            playlist = fileViewerViewModel.playlist,
            imageLoader = imageViewModel.imageLoader!!,
            rotationAngles = imageViewModel.rotationAngles,
            onSingleTap = { toggleOverlay() }
        )
        binding.imageViewerPager.adapter = pagerAdapter
        binding.imageViewerPager.offscreenPageLimit = 1
        binding.imageViewerPager.setCurrentItem(fileViewerViewModel.currentPlaylistIndex, false)
        updateFileName()

        binding.imageViewerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                fileViewerViewModel.currentPlaylistIndex = position
                fileViewerViewModel.filePath = fileViewerViewModel.playlist[position].fullPath
                updateFileName()
                if (slideshowActive) {
                    handler.removeCallbacks(slideshowNext)
                    handler.postDelayed(slideshowNext, Constants.SLIDESHOW_DELAY)
                }
            }
        })
    }

    private fun updateFileName() {
        binding.textFilename.text = File(fileViewerViewModel.filePath!!).name
    }

    private fun toggleOverlay() {
        handler.removeCallbacks(hideUI)
        if (binding.overlay.isGone) {
            binding.overlay.visibility = View.VISIBLE
            showPartialSystemUi()
            handler.postDelayed(hideUI, hideDelay)
        } else {
            hideUI.run()
        }
    }

    private fun goToPage(delta: Int) {
        val size = fileViewerViewModel.playlist.size
        if (size == 0) return
        val next = (binding.imageViewerPager.currentItem + delta).mod(size)
        binding.imageViewerPager.setCurrentItem(next, true)
    }

    private fun currentPath() = fileViewerViewModel.playlist[binding.imageViewerPager.currentItem].fullPath

    private fun currentPageViewHolder(): ImageViewerPagerAdapter.PageViewHolder? {
        val recyclerView = binding.imageViewerPager.getChildAt(0) as? RecyclerView
        return recyclerView?.findViewHolderForAdapterPosition(binding.imageViewerPager.currentItem) as? ImageViewerPagerAdapter.PageViewHolder
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        handler.removeCallbacks(hideUI)
        handler.postDelayed(hideUI, hideDelay)
    }

    private fun onClickRotate(angle: Float) {
        val path = currentPath()
        imageViewModel.rotationAngles[path] = (imageViewModel.rotationAngles[path] ?: 0f) + angle
        currentPageViewHolder()?.imageView?.restoreZoomNormal()
        pagerAdapter.notifyItemChanged(binding.imageViewerPager.currentItem)
    }

    private fun stopSlideshow() {
        slideshowActive = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Toast.makeText(this, R.string.slideshow_stopped, Toast.LENGTH_SHORT).show()
    }

    class OrientationTransformation(private val orientation: Float): Transformation() {
        lateinit var bitmap: coil3.Bitmap

        override val cacheKey = "rot$orientation"

        override suspend fun transform(input: coil3.Bitmap, size: Size): coil3.Bitmap {
            return coil3.Bitmap.createBitmap(input, 0, 0, input.width, input.height, Matrix().apply {
                postRotate(orientation)
            }, true).also {
                bitmap = it
            }
        }
    }

    private fun askSaveRotation(callback: () -> Unit) {
        val path = currentPath()
        val angle = imageViewModel.rotationAngles[path] ?: 0f
        if (angle.mod(360f) != 0f && !slideshowActive) {
            val transformation = pagerAdapter.orientationTransformations[path]
            CustomAlertDialogBuilder(this, theme)
                .keepFullScreen()
                .setTitle(R.string.warning)
                .setMessage(R.string.ask_save_img_rotated)
                .setNegativeButton(R.string.no) { _, _ ->
                    imageViewModel.rotationAngles[path] = 0f
                    callback()
                }
                .setNeutralButton(R.string.cancel, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    val outputStream = ByteArrayOutputStream()
                    if (transformation?.bitmap?.compress(
                            if (path.endsWith("png", true)) {
                                Bitmap.CompressFormat.PNG
                            } else {
                                Bitmap.CompressFormat.JPEG
                            }, 90, outputStream
                        ) == true
                    ) {
                        if (encryptedVolume.importFile(ByteArrayInputStream(outputStream.toByteArray()), path)) {
                            Toast.makeText(this, R.string.image_saved_successfully, Toast.LENGTH_SHORT).show()
                            imageViewModel.rotationAngles[path] = 0f
                            callback()
                        } else {
                            CustomAlertDialogBuilder(this, theme)
                                .keepFullScreen()
                                .setTitle(R.string.error)
                                .setMessage(R.string.file_write_failed)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                    } else {
                        CustomAlertDialogBuilder(this, theme)
                            .keepFullScreen()
                            .setTitle(R.string.error)
                            .setMessage(R.string.bitmap_compress_failed)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                }
                .show()
        } else {
            callback()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        currentPageViewHolder()?.imageView?.restoreZoomNormal()
    }
}
