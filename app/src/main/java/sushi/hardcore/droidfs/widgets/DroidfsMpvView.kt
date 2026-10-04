package sushi.hardcore.droidfs.widgets

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.MPVLib

/**
 * 引擎: mpv-android 官方源码(2026-09-17)自己编译的 libmpv + libplayer + MPVLib(is.xyz.mpv),
 * 原生库的编译步骤见 mpv-native/ 和 .github/workflows/build-release.yml。
 * MPVLib 只是底层 JNI 绑定, 不带 View 基类, 所以 Surface 生命周期管理这部分在这里自己写。
 * 下面的逻辑是照抄 mpv-android 官方项目里 BaseMPVView.kt 的源码搬过来的:
 * https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/BaseMPVView.kt
 * (只是把继承关系去掉, 直接实现在这一个类里)
 */
class DroidfsMpvView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var pendingFilePath: String? = null
    private var isSurfaceReady = false
    private var voInUse: String = "gpu"

    /** 初始化 libmpv, 在 Activity onCreate 里调一次 */
    fun initialize(configDir: String, cacheDir: String) {
        writeFontsConf(configDir, cacheDir)
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

    /**
     * 写 fonts.conf, 让字幕渲染库(libass/fontconfig)能找到系统字体。
     * 和 mpv-android 的 Utils.writeFontsConf 完全一样; 没有它, 中日韩字幕会显示成空白或方块。
     */
    private fun writeFontsConf(configDir: String, cacheDir: String) {
        val parts = listOf(
            "<fontconfig>",
            "<dir>/system/fonts/</dir>",
            "<dir>/product/fonts/</dir>",
            "<cachedir>$cacheDir</cachedir>",
            "<alias><family>serif</family>",
            "<prefer><family>Noto Serif</family></prefer>",
            "</alias>",
            "<alias><family>sans-serif</family>",
            "<prefer>",
            "<family>Roboto</family>",
            "<family>Noto Sans</family>",
            "</prefer>",
            "</alias>",
            "<alias><family>monospace</family>",
            "<prefer><family>Droid Sans Mono</family></prefer>",
            "</alias>",
            "</fontconfig>"
        )
        try {
            java.io.File("$configDir/fonts.conf").writeText(parts.joinToString("\n"))
        } catch (e: java.io.IOException) {
            Log.w("DroidfsMpvView", "Failed to write fonts.conf", e)
        }
    }

    /** 销毁 libmpv, 在 Activity onDestroy 里调一次 */
    fun destroy() {
        holder.removeCallback(this)
        MPVLib.destroy()
    }

    private fun initOptions() {
        // ---- 以下全部照抄 mpv-android 官方播放器(MPVView.initOptions)在默认设置下的值 ----
        MPVLib.setOptionString("profile", "fast") // 手机优化预设
        MPVLib.setOptionString("vo", "gpu")

        // 显示器刷新率, 官方也是这样上报给 mpv
        val refreshRate = ContextCompat.getDisplayOrDefault(context).mode.refreshRate
        MPVLib.setOptionString("display-fps-override", refreshRate.toString())

        MPVLib.setOptionString("video-sync", "audio")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("input-default-bindings", "yes")
        // 官方限制了解复用缓存, 因为 mpv 默认值对手机太大
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        // 官方播放器自己调用 write-watch-later, 这里不需要
        MPVLib.setOptionString("save-position-on-quit", "no")

        // ---- 以下是 DroidFS 自己的界面需要的, 和官方不同 ----
        // 播完停在最后一帧, 由 VideoPlayer 检测接近结尾后手动切下一个
        MPVLib.setOptionString("keep-open", "yes")
        // DroidFS 自己做了控制栏, mpv 自带的 OSD/OSC 要关掉, 不然会叠在一起
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
