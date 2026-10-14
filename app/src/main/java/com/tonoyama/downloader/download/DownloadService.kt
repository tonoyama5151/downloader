package com.tonoyama.downloader.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ダウンロード中にアプリがバックグラウンドへ回っても止まらないようにするサービス */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastStartId = 0
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ServiceCompat.startForeground(
            this,
            Notifications.PROGRESS_ID,
            Notifications.progress(this, DownloadRepository.tasks.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!observing) {
            observing = true
            scope.launch {
                DownloadRepository.tasks.collect { tasks ->
                    if (tasks.none { it.isActive }) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelfResult(lastStartId)
                    } else {
                        Notifications.update(this@DownloadService, Notifications.progress(this@DownloadService, tasks))
                    }
                    delay(1_000)
                }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 以降、データ同期サービスには1日の実行時間制限がある
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadRepository.pauseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
