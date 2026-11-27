package sushi.hardcore.droidfs.widgets

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.file_operations.FileOperationService
import sushi.hardcore.droidfs.util.PathUtils

/** 查重结果。默认什么都不勾选, 并且每组至少要保留一个文件。 */
class DuplicatesDialog(
    private val context: Context,
    private val report: FileOperationService.DuplicateReport,
    private val onDelete: (toDelete: List<ExplorerElement>, keptCopies: List<List<ExplorerElement>>) -> Unit,
) {
    private sealed class Row {
        class Text(val text: String) : Row()
        class File(val element: ExplorerElement, val group: List<ExplorerElement>) : Row()
    }

    private val selected = HashSet<ExplorerElement>()
    private val rows = ArrayList<Row>().apply {
        val wasted = report.groups.sumOf { it[0].stat.size * (it.size - 1) }
        add(Row.Text(
            if (report.groups.isEmpty()) {
                context.getString(R.string.no_duplicates_found)
            } else {
                context.getString(R.string.duplicates_summary, report.groups.size, PathUtils.formatSize(wasted))
            }
        ))
        if (report.unreadable.isNotEmpty()) {
            add(Row.Text(context.getString(R.string.duplicates_unreadable, report.unreadable.size)))
            report.unreadable.forEach { add(Row.Text(it)) }
        }
        report.groups.forEachIndexed { i, group ->
            add(Row.Text(context.getString(R.string.duplicates_group, i + 1, group.size, PathUtils.formatSize(group[0].stat.size))))
            group.forEach { add(Row.File(it, group)) }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.File) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            return when (val row = rows[position]) {
                is Row.Text -> ((convertView as TextView?) ?: MaterialTextView(context).apply {
                    setPadding(padding, padding / 2, padding, padding / 4)
                }).apply { text = row.text }
                is Row.File -> ((convertView as CheckBox?) ?: MaterialCheckBox(context).apply {
                    setPadding(padding / 4, padding / 4, padding / 4, padding / 4)
                }).apply {
                    // 复用的视图先摘掉旧监听, 避免改勾选状态时误触发
                    setOnCheckedChangeListener(null)
                    text = row.element.fullPath
                    isChecked = row.element in selected
                    setOnCheckedChangeListener { button, checked ->
                        if (!checked) {
                            selected.remove(row.element)
                        } else if (row.group.all { it == row.element || it in selected }) {
                            button.isChecked = false
                            Toast.makeText(context, R.string.duplicates_keep_one, Toast.LENGTH_SHORT).show()
                        } else {
                            selected.add(row.element)
                        }
                    }
                }
            }
        }
    }

    fun show() {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.duplicates_result_title)
            .setView(object : ListView(context) {
                override fun onMeasure(w: Int, h: Int) {
                    var max = resources.displayMetrics.heightPixels / 2
                    if (View.MeasureSpec.getMode(h) != View.MeasureSpec.UNSPECIFIED) max = minOf(max, View.MeasureSpec.getSize(h))
                    super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
                }
            }.also {
                it.adapter = adapter
                it.divider = null
            })
            .setCancelable(false)
            .setBackgroundInsetTop((8 * context.resources.displayMetrics.density).toInt())
            .setNegativeButton(R.string.cancel, null)
        if (report.groups.isNotEmpty()) {
            builder.setPositiveButton(R.string.duplicates_delete, null)
                .setNeutralButton(R.string.duplicates_select_extra, null)
        }
        val dialog = builder.show()
        dialog.window?.setGravity(Gravity.TOP)
        // 在 show 之后设置监听, 点击按钮时对话框才不会自动关闭
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            selected.clear()
            report.groups.forEach { selected.addAll(it.drop(1)) }
            adapter.notifyDataSetChanged()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { confirmDelete(dialog) }
    }

    private fun confirmDelete(resultDialog: AlertDialog) {
        if (selected.isEmpty()) {
            Toast.makeText(context, R.string.duplicates_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.warning)
            .setMessage(context.getString(R.string.duplicates_delete_confirm, selected.size))
            .setPositiveButton(R.string.ok) { _, _ ->
                resultDialog.dismiss()
                onDelete(
                    selected.toList(),
                    report.groups.filter { group -> group.any { it in selected } }
                        .map { group -> group.filter { it !in selected } },
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
