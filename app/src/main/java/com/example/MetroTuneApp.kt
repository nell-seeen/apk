package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import com.example.data.db.AppDatabase
import com.example.data.network.InnerTubeClient
import com.example.data.network.LyricsClient
import com.example.download.DownloadManager
import com.example.player.MusicPlayerManager
import com.example.service.PlaybackService

class MetroTuneApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var innerTubeClient: InnerTubeClient
        private set

    lateinit var lyricsClient: LyricsClient
        private set

    lateinit var downloadManager: DownloadManager
        private set

    lateinit var playerManager: MusicPlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        innerTubeClient = InnerTubeClient()
        lyricsClient = LyricsClient()
        downloadManager = DownloadManager(this, innerTubeClient, database)
        playerManager = MusicPlayerManager.getInstance(this, innerTubeClient, downloadManager, database)

        // Start playback service to attach MediaSession
        try {
            val intent = Intent(this, PlaybackService::class.java)
            startService(intent)
        } catch (e: Exception) {
            // Android 8+ background start restriction if applicable
        }
    }

    companion object {
        fun get(context: Context): MetroTuneApp {
            return context.applicationContext as MetroTuneApp
        }
    }
}
