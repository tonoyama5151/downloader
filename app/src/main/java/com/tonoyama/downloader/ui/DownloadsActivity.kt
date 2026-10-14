package com.tonoyama.downloader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tonoyama.downloader.R
import com.tonoyama.downloader.databinding.ActivityDownloadsBinding
import com.tonoyama.downloader.databinding.ItemDownloadBinding
import com.tonoyama.downloader.download.DownloadRepository
import com.tonoyama.downloader.download.DownloadTask
import com.tonoyama.downloader.download.TaskStatus
import kotlinx.coroutines.launch

class DownloadsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDownloadsBinding
    private val adapter = TaskAdapter(
        onAction = { task -> onAction(task) },
        onDelete = { task -> confirmDelete(task) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            b.list.setPadding(b.list.paddingLeft, b.list.paddingTop, b.list.paddingRight, bars.bottom)
            insets
        }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_completed) {
                DownloadRepository.clearCompleted()
                true
            } else {
                false
            }
        }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadRepository.tasks.collect { tasks ->
                    adapter.submitList(tasks)
                    b.empty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun onAction(task: DownloadTask) {
        when (task.status) {
            TaskStatus.QUEUED, TaskStatus.RUNNING -> DownloadRepository.pause(task.id)
            TaskStatus.PAUSED, TaskStatus.FAILED -> DownloadRepository.resume(task.id)
            TaskStatus.COMPLETED -> open(task)
            TaskStatus.MERGING -> Unit
        }
    }

    private fun open(task: DownloadTask) {
        val uri = task.outputUri?.let(Uri::parse) ?: return
        val type = contentResolver.getType(uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "このファイルを開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "ファイルが移動または削除されています", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(task: DownloadTask) {
        val builder = MaterialAlertDialogBuilder(this).setTitle(task.title)
        if (task.status == TaskStatus.COMPLETED) {
            builder.setMessage("一覧から削除します。保存したファイルも削除しますか？")
                .setPositiveButton("ファイルも削除") { _, _ -> DownloadRepository.remove(task.id, deleteFile = true) }
                .setNeutralButton("一覧からのみ") { _, _ -> DownloadRepository.remove(task.id, deleteFile = false) }
                .setNegativeButton("キャンセル", null)
        } else {
            builder.setMessage("ダウンロードを中止して、途中までのデータを削除します。")
                .setPositiveButton("削除") { _, _ -> DownloadRepository.remove(task.id, deleteFile = false) }
                .setNegativeButton("キャンセル", null)
        }
        builder.show()
    }

    private class TaskAdapter(
        private val onAction: (DownloadTask) -> Unit,
        private val onDelete: (DownloadTask) -> Unit,
    ) : ListAdapter<DownloadTask, TaskAdapter.Holder>(Diff) {

        class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val task = getItem(position)
            val v = holder.binding
            v.title.text = task.title
            v.status.text = task.statusLabel()

            val percent = task.progressPercent
            when {
                task.status == TaskStatus.COMPLETED -> v.progress.visibility = View.GONE
                task.status == TaskStatus.RUNNING && percent == null || task.status == TaskStatus.MERGING -> {
                    v.progress.visibility = View.VISIBLE
                    if (!v.progress.isIndeterminate) {
                        v.progress.visibility = View.INVISIBLE
                        v.progress.isIndeterminate = true
                        v.progress.visibility = View.VISIBLE
                    }
                }
                else -> {
                    v.progress.visibility = View.VISIBLE
                    if (v.progress.isIndeterminate) {
                        v.progress.visibility = View.INVISIBLE
                        v.progress.isIndeterminate = false
                        v.progress.visibility = View.VISIBLE
                    }
                    v.progress.setProgressCompat(percent ?: 0, false)
                }
            }

            val (label, icon) = when (task.status) {
                TaskStatus.QUEUED, TaskStatus.RUNNING -> "一時停止" to R.drawable.ic_pause
                TaskStatus.PAUSED -> "再開" to R.drawable.ic_play
                TaskStatus.FAILED -> "再試行" to R.drawable.ic_refresh
                TaskStatus.COMPLETED -> "開く" to R.drawable.ic_open
                TaskStatus.MERGING -> "処理中" to R.drawable.ic_download
            }
            v.action.text = label
            v.action.setIconResource(icon)
            v.action.isEnabled = task.status != TaskStatus.MERGING
            v.action.setOnClickListener { onAction(task) }
            v.delete.isEnabled = task.status != TaskStatus.MERGING
            v.delete.setOnClickListener { onDelete(task) }
        }

        object Diff : DiffUtil.ItemCallback<DownloadTask>() {
            override fun areItemsTheSame(oldItem: DownloadTask, newItem: DownloadTask) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DownloadTask, newItem: DownloadTask) = oldItem == newItem
        }
    }
}
