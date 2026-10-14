package com.tonoyama.downloader.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tonoyama.downloader.R
import com.tonoyama.downloader.ui.DownloadsActivity

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_RESULT = "result"
    const val PROGRESS_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "ダウンロードの進捗", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "ダウンロード完了", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun downloadsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.isActive }
        val current = active.firstOrNull { it.status == TaskStatus.RUNNING || it.status == TaskStatus.MERGING }
            ?: active.firstOrNull()
        val percents = active.mapNotNull { it.progressPercent }
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (active.isEmpty()) "ダウンロード準備中" else "ダウンロード中（${active.size}件）")
            .setContentText(current?.let { "${it.fileName} · ${it.statusLabel()}" } ?: "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(downloadsIntent(context))
        if (percents.isNotEmpty()) {
            builder.setProgress(100, percents.average().toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun update(context: Context, notification: Notification) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(PROGRESS_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun finished(context: Context, task: DownloadTask) {
        if (!canNotify(context)) return
        val ok = task.status == TaskStatus.COMPLETED
        val contentIntent = if (ok && task.outputUri != null) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(task.outputUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(
                context,
                task.id.hashCode(),
                view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            downloadsIntent(context)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (ok) "保存しました" else "ダウンロードに失敗しました")
            .setContentText(if (ok) task.title else "${task.fileName}：${task.error ?: ""}")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }
}
