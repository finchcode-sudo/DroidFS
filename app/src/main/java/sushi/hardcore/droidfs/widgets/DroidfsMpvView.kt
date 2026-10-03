package sushi.hardcore.droidfs.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

/**
 * dev.jdtech.mpv:libmpv 这个库只导出 MPVLib(底层 JNI 绑定), 不导出任何 View 基类,
 * 所以 Surface 生命周期管理这部分要自己写。
 * 下面的逻辑是照抄 mpv-android 官方项目里 BaseMPVView.kt 的真实源码搬过来的:
 * https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/BaseMPVView.kt
 * (只是把继承关系去掉, 直接实现在这一个类里)
 */
class DroidfsMpvView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var pendingFilePath: String? = null
    private var isSurfaceReady = false
    private var voInUse: String = "gpu"

    /** 初始化 libmpv, 在 Activity onCreate 里调一次 */
    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context.applicationContext)

        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) {
            MPVLib.setOptionString(opt, cacheDir)
        }

        initOptions()
        MPVLib.init()

        // 在 surfaceCreated 之前设置可能会搞乱 VO 初始化
        MPVLib.setOptionString("force-window", "no")
        // playFile() 的逻辑要求至少 idle 一次
        MPVLib.setOptionString("idle", "once")

        holder.addCallback(this)
    }

    /** 销毁 libmpv, 在 Activity onDestroy 里调一次 */
    fun destroy() {
        holder.removeCallback(this)
        MPVLib.destroy()
    }

    private fun initOptions() {
        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy,no")
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("keepaspect", "yes")
        MPVLib.setOptionString("video-rotate", "auto") // 自动读取视频里的旋转标记(比如手机竖拍的横向素材)
        MPVLib.setOptionString("tls-verify", "no")
        MPVLib.setOptionString("http-allow-redirect", "yes")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("cache-secs", "60")
        MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        // 我们自己做了一套控制栏(播放/暂停/进度条), mpv 自带的那一套要关掉, 不然两个会叠在一起显示
        MPVLib.setOptionString("osd-level", "0")
        MPVLib.setOptionString("osc", "no")
    }

    /** 加载一个地址; surface 还没建好之前调用会先记下来, 等 surface 建好后自动播放 */
    fun loadUrl(url: String) {
        if (isSurfaceReady) {
            MPVLib.command(arrayOf("loadfile", url))
        } else {
            pendingFilePath = url
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(holder.surface)
        // 强制 mpv 往这个 surface 上画字幕/OSD, 即便正常情况下它可能不会画
        MPVLib.setOptionString("force-window", "yes")
        isSurfaceReady = true

        val path = pendingFilePath
        if (path != null) {
            MPVLib.command(arrayOf("loadfile", path))
            pendingFilePath = null
        } else {
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        isSurfaceReady = false
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }

    companion object {
        private const val TAG = "DroidfsMpv"
    }
}
