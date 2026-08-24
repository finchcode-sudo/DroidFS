package sushi.hardcore.droidfs.file_viewers

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.widgets.ZoomableImageView

class ImageViewerPagerAdapter(
    private val playlist: List<ExplorerElement>,
    private val imageLoader: ImageLoader,
    private val rotationAngles: MutableMap<String, Float>,
    private val onSingleTap: () -> Unit,
) : RecyclerView.Adapter<ImageViewerPagerAdapter.PageViewHolder>() {

    val orientationTransformations = mutableMapOf<String, ImageViewer.OrientationTransformation>()

    class PageViewHolder(val imageView: ZoomableImageView) : RecyclerView.ViewHolder(imageView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val imageView = ZoomableImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return PageViewHolder(imageView)
    }

    override fun getItemCount() = playlist.size

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val path = playlist[position].fullPath
        holder.imageView.resetZoomFactor()
        holder.imageView.setOnInteractionListener(object : ZoomableImageView.OnInteractionListener {
            override fun onSingleTap(event: MotionEvent?) { onSingleTap() }
            override fun onTouch(event: MotionEvent?) {}
        })

        val requestBuilder = ImageRequest.Builder(holder.imageView.context)
            .data(path)
            .target(holder.imageView)
            .placeholder(holder.imageView.drawable)
            .crossfade(150)

        val angle = rotationAngles[path] ?: 0f
        if (angle.mod(360f) != 0f) {
            val transformation = ImageViewer.OrientationTransformation(angle)
            orientationTransformations[path] = transformation
            requestBuilder.transformations(transformation)
        }

        imageLoader.enqueue(requestBuilder.build())
    }
}
