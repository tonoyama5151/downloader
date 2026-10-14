package com.tonoyama.downloader

import android.app.Application
import com.tonoyama.downloader.download.DownloadRepository
import com.tonoyama.downloader.download.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        DownloadRepository.init(this)
    }
}
