package com.example.download

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.DownloadEntity
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadStatus
import com.example.data.model.Song
import com.example.data.network.InnerTubeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val context: Context,
    private val innerTubeClient: InnerTubeClient,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "DownloadManager"
        const val MAX_CONCURRENT_DOWNLOADS = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloadTasks = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadItem>> = _downloadTasks.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadDir: File by lazy {
        File(context.filesDir, "downloads").apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        // Load existing downloads from database to initialize status
        scope.launch {
            database.downloadDao().getAllDownloads().collect { list ->
                val current = _downloadTasks.value.toMutableMap()
                for (entity in list) {
                    val file = File(entity.filePath)
                    if (file.exists() && file.length() > 0) {
                        current[entity.songId] = DownloadItem(
                            song = entity.toSong(),
                            status = DownloadStatus.COMPLETED,
                            progress = 1.0f,
                            bytesDownloaded = entity.fileSize,
                            totalBytes = entity.fileSize
                        )
                    } else {
                        // File missing on disk, remove from DB
                        database.downloadDao().delete(entity.songId)
                    }
                }
                _downloadTasks.value = current
            }
        }
    }

    fun startDownload(song: Song) {
        if (isDownloaded(song.id)) {
            Log.d(TAG, "[DOWNLOAD] Song ${song.title} is already downloaded")
            return
        }

        val currentItem = _downloadTasks.value[song.id]
        if (currentItem?.status == DownloadStatus.DOWNLOADING || currentItem?.status == DownloadStatus.PENDING) {
            Log.d(TAG, "[DOWNLOAD] Song ${song.title} is already in queue")
            return
        }

        updateTask(song.id, DownloadItem(song = song, status = DownloadStatus.PENDING))

        val job = scope.launch {
            semaphore.acquire()
            try {
                if (!isActive) return@launch
                executeDownload(song)
            } finally {
                semaphore.release()
                activeJobs.remove(song.id)
            }
        }
        activeJobs[song.id] = job
    }

    private suspend fun executeDownload(song: Song) {
        val targetFile = File(downloadDir, "${song.id}.m4a")
        val tempFile = File(downloadDir, "${song.id}.tmp")

        try {
            updateTask(song.id, DownloadItem(song = song, status = DownloadStatus.DOWNLOADING, progress = 0.05f))

            // 1. Resolve audio stream URL
            val streamUrl = innerTubeClient.getStreamUrl(song.id)
            if (streamUrl.isNullOrEmpty()) {
                throw IllegalStateException("Unable to resolve audio stream for download")
            }

            // 2. Fetch and write to temp file
            val request = Request.Builder().url(streamUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} downloading audio stream")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val contentLength = body.contentLength()

            if (tempFile.exists()) tempFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastUpdate = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!currentCoroutineContext().isActive) {
                            tempFile.delete()
                            updateTask(song.id, DownloadItem(song = song, status = DownloadStatus.CANCELLED))
                            return
                        }

                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            val progress = if (contentLength > 0) totalRead.toFloat() / contentLength else 0.5f
                            updateTask(
                                song.id,
                                DownloadItem(
                                    song = song,
                                    status = DownloadStatus.DOWNLOADING,
                                    progress = progress,
                                    bytesDownloaded = totalRead,
                                    totalBytes = contentLength
                                )
                            )
                            lastUpdate = now
                        }
                    }
                    output.flush()
                }
            }

            // 3. Atomic rename temp file to target file
            if (targetFile.exists()) targetFile.delete()
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val finalSize = targetFile.length()

            // 4. Save to Database
            val entity = DownloadEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationSeconds = song.durationSeconds,
                durationFormatted = song.durationFormatted,
                thumbnailUrl = song.thumbnailUrl,
                filePath = targetFile.absolutePath,
                fileSize = finalSize
            )
            database.downloadDao().insert(entity)

            updateTask(
                song.id,
                DownloadItem(
                    song = song.copy(isDownloaded = true, localFilePath = targetFile.absolutePath),
                    status = DownloadStatus.COMPLETED,
                    progress = 1.0f,
                    bytesDownloaded = finalSize,
                    totalBytes = finalSize
                )
            )
            Log.d(TAG, "[DOWNLOAD] Successfully downloaded ${song.title} (${finalSize / 1024} KB)")

        } catch (e: Exception) {
            if (e is CancellationException) {
                tempFile.delete()
                updateTask(song.id, DownloadItem(song = song, status = DownloadStatus.CANCELLED))
                Log.d(TAG, "[DOWNLOAD] Download cancelled for ${song.title}")
            } else {
                tempFile.delete()
                updateTask(
                    song.id,
                    DownloadItem(
                        song = song,
                        status = DownloadStatus.FAILED,
                        error = e.message ?: "Download failed"
                    )
                )
                Log.e(TAG, "[DOWNLOAD] Failed to download ${song.title}: ${e.message}")
            }
        }
    }

    fun cancelDownload(songId: String) {
        activeJobs[songId]?.cancel()
        activeJobs.remove(songId)
        val item = _downloadTasks.value[songId]
        if (item != null) {
            updateTask(songId, item.copy(status = DownloadStatus.CANCELLED))
        }
        File(downloadDir, "$songId.tmp").delete()
    }

    fun deleteDownload(songId: String) {
        cancelDownload(songId)
        scope.launch {
            val file = File(downloadDir, "$songId.m4a")
            if (file.exists()) file.delete()
            database.downloadDao().delete(songId)

            val current = _downloadTasks.value.toMutableMap()
            current.remove(songId)
            _downloadTasks.value = current
            Log.d(TAG, "[DOWNLOAD] Deleted downloaded song $songId")
        }
    }

    fun isDownloaded(songId: String): Boolean {
        val item = _downloadTasks.value[songId]
        if (item?.status == DownloadStatus.COMPLETED) {
            val file = File(downloadDir, "$songId.m4a")
            return file.exists() && file.length() > 0
        }
        return false
    }

    fun getLocalFilePath(songId: String): String? {
        val file = File(downloadDir, "$songId.m4a")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun getDownloadsSize(): Long {
        return downloadDir.listFiles()?.filter { it.extension == "m4a" }?.sumOf { it.length() } ?: 0L
    }

    fun getCacheSize(): Long {
        val cacheDir = context.cacheDir
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearCache() {
        context.cacheDir.deleteRecursively()
        context.cacheDir.mkdirs()
        Log.d(TAG, "[APP] App cache cleared")
    }

    private fun updateTask(songId: String, item: DownloadItem) {
        val current = _downloadTasks.value.toMutableMap()
        current[songId] = item
        _downloadTasks.value = current
    }
}
