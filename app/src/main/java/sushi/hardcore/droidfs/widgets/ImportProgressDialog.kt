package sushi.hardcore.droidfs.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.file_operations.FileOperationService

/**
 * 分享导入时显示的进度弹窗: 文件名 / 百分比 / 进度条 / 保持屏幕常亮 / 取消 / 后台运行。
 * "后台运行" 只是关掉弹窗, 导入任务继续在服务里跑(通知栏可见); "取消" 会中断导入任务。
 */
class ImportProgressDialog(
    context: Context,
    private val progress: FileOperationService.ImportProgress,
    onCancel: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val view = LayoutInflater.from(context).inflate(R.layout.dialog_import_progress, null)
    private val textName = view.findViewById<TextView>(R.id.text_name)
    private val textPercent = view.findViewById<TextView>(R.id.text_percent)
    private val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
    private val checkKeepOn = view.findViewById<CheckBox>(R.id.check_keep_screen_on)
    private val context = context

    private val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.import_progress_title, 0, 0))
        .setView(view)
        .setCancelable(false)
        .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
        .setPositiveButton(R.string.run_in_background, null)
        .create()

    private val updater = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 200)
        }
    }

    init {
        dialog.setOnDismissListener { handler.removeCallbacks(updater) }
        checkKeepOn.setOnCheckedChangeListener { _, checked ->
            dialog.window?.let {
                if (checked) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun update() {
        dialog.setTitle(context.getString(R.string.import_progress_title, progress.fileIndex, progress.fileCount))
        textName.text = progress.fileName
        val total = progress.totalBytes
        if (total > 0) {
            val percent = (progress.doneBytes * 100 / total).toInt().coerceIn(0, 100)
            progressBar.isIndeterminate = false
            progressBar.max = 100
            progressBar.progress = percent
            textPercent.text = "$percent%"
        } else {
            progressBar.isIndeterminate = true
            textPercent.text = ""
        }
    }

    fun show() {
        dialog.show()
        update()
        handler.postDelayed(updater, 200)
    }

    fun dismiss() {
        handler.removeCallbacks(updater)
        if (dialog.isShowing) dialog.dismiss()
    }
}
