package sushi.hardcore.droidfs

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import sushi.hardcore.droidfs.content_providers.TemporaryFileProvider
import sushi.hardcore.droidfs.util.AndroidUtils

class VolumeManagerApp : Application(), DefaultLifecycleObserver {
    companion object {
        const val ACTION_CLOSE_ALL_VOLUMES = "close_all"
    }

    private val keepAliveServiceStartIntent by lazy {
        Intent(this, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_START
        }.putExtra(
            "notification", KeepAliveService.NotificationDetails(
                "KeepAlive",
                getString(R.string.keep_alive_notification_title),
                getString(R.string.keep_alive_notification_text),
                KeepAliveService.NotificationDetails.NotificationAction(
                    R.drawable.icon_lock,
                    getString(R.string.close_all),
                    ACTION_CLOSE_ALL_VOLUMES,
                )
            )
        )
    }
    /**
     * 应用进入后台多少秒后锁定所有卷: 0 = 立即, -1 = 从不, 其余为秒数。
     * 设置里的 "auto_lock" 是字符串值。
     */
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val autoLockSeconds: Long
        get() = prefs.getString("auto_lock", "0")?.toLongOrNull() ?: 0L
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "auto_lock") {
            updateServicesStates()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundSince = 0L
    private val lockRunnable = Runnable { lockNow() }
    var isExporting = false
    var isStartingExternalApp = false
    val volumeManager = VolumeManager(this).also {
        it.observe(object : VolumeManager.Observer {
            override fun onVolumeStateChanged(volume: VolumeData) {
                updateServicesStates()
            }

            override fun onAllVolumesClosed() {
                stopKeepAliveService()
            }
        })
    }

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun updateServicesStates() {
        // 只要不是"立即"锁定, 就需要前台服务来保持进程存活, 卷才不会随着窗口关闭而丢失
        if (autoLockSeconds != 0L && volumeManager.getVolumeCount() > 0) {
            if (!AndroidUtils.isServiceRunning(this@VolumeManagerApp, KeepAliveService::class.java)) {
                ContextCompat.startForegroundService(this, keepAliveServiceStartIntent)
            }
        } else {
            stopKeepAliveService()
        }
    }

    private fun stopKeepAliveService() {
        stopService(Intent(this, KeepAliveService::class.java))
    }

    private fun lockNow() {
        handler.removeCallbacks(lockRunnable)
        backgroundSince = 0L
        volumeManager.closeAll()
        TemporaryFileProvider.instance.wipe()
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(lockRunnable)
        val timeout = autoLockSeconds
        // 回到前台时, 如果在后台待的时间已经超过设定值(定时器可能因休眠被推迟), 立刻锁定
        if (backgroundSince != 0L && timeout > 0 &&
            SystemClock.elapsedRealtime() - backgroundSince >= timeout * 1000
        ) {
            lockNow()
        }
        backgroundSince = 0L
    }

    override fun onResume(owner: LifecycleOwner) {
        isStartingExternalApp = false
    }

    override fun onStop(owner: LifecycleOwner) {
        if (isStartingExternalApp) return
        val timeout = autoLockSeconds
        when {
            timeout == 0L -> {
                volumeManager.closeAll()
                TemporaryFileProvider.instance.wipe()
            }
            else -> {
                if (!isExporting) {
                    TemporaryFileProvider.instance.wipe()
                }
                if (timeout > 0) {
                    backgroundSince = SystemClock.elapsedRealtime()
                    handler.postDelayed(lockRunnable, timeout * 1000)
                }
            }
        }
    }
}
