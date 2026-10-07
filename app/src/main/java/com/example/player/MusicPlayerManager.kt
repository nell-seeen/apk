package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.db.AppDatabase
import com.example.data.db.HistoryEntity
import com.example.data.model.Song
import com.example.data.network.InnerTubeClient
import com.example.download.DownloadManager
import com.example.innertube.extractor.StreamExtractor
import com.example.innertube.models.AudioStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class RepeatMode {
    OFF, ALL, ONE
}

enum class PlayerStatus {
    IDLE, BUFFERING, READY, ENDED
}

class MusicPlayerManager(
    private val context: Context,
    private val innerTubeClient: InnerTubeClient,
    private val downloadManager: DownloadManager,
    private val database: AppDatabase,
    private val streamExtractor: StreamExtractor = innerTubeClient.streamExtractor
) : Player.Listener {

    companion object {
        private const val TAG = "MusicPlayerManager"
        private const val MAX_STREAM_RETRIES = 2

        @Volatile
        private var INSTANCE: MusicPlayerManager? = null

        fun getInstance(
            context: Context,
            innerTubeClient: InnerTubeClient,
            downloadManager: DownloadManager,
            database: AppDatabase,
            streamExtractor: StreamExtractor = innerTubeClient.streamExtractor
        ): MusicPlayerManager {
            return INSTANCE ?: synchronized(this) {
                val instance = MusicPlayerManager(
                    context.applicationContext,
                    innerTubeClient,
                    downloadManager,
                    database,
                    streamExtractor
                )
                INSTANCE = instance
                instance
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val exoPlayer: ExoPlayer by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                addListener(this@MusicPlayerManager)
            }
    }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _currentStream = MutableStateFlow<AudioStream?>(null)
    val currentStream: StateFlow<AudioStream?> = _currentStream.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playerStatus = MutableStateFlow(PlayerStatus.IDLE)
    val playerStatus: StateFlow<PlayerStatus> = _playerStatus.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isLoadingStream = MutableStateFlow(false)
    val isLoadingStream: StateFlow<Boolean> = _isLoadingStream.asStateFlow()

    private var progressJob: Job? = null
    private var lastRecordedSongId: String? = null
    private var streamRetryCount = 0

    init {
        startProgressTracking()
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    _currentPosition.value = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val dur = exoPlayer.duration
                    if (dur > 0) {
                        _duration.value = dur
                    }
                }
                delay(400)
            }
        }
    }

    fun playSong(song: Song, newQueue: List<Song> = listOf(song), startIndex: Int = 0) {
        _queue.value = newQueue
        _currentIndex.value = startIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        streamRetryCount = 0
        playSongInternal(song, forceRefresh = false)
    }

    fun playQueueIndex(index: Int) {
        val list = _queue.value
        if (index in list.indices) {
            _currentIndex.value = index
            streamRetryCount = 0
            playSongInternal(list[index], forceRefresh = false)
        }
    }

    private fun playSongInternal(song: Song, forceRefresh: Boolean = false, seekPositionMs: Long = 0L) {
        _currentSong.value = song
        _currentPosition.value = seekPositionMs
        _duration.value = (song.durationSeconds * 1000L).coerceAtLeast(0L)
        _isLoadingStream.value = true

        scope.launch {
            try {
                // 1. Check if offline file exists (Always prioritize offline local file!)
                val localPath = downloadManager.getLocalFilePath(song.id)
                val mediaUri = if (localPath != null && File(localPath).exists()) {
                    Log.d(TAG, "[PLAYER] Playing offline downloaded local file: $localPath")
                    _currentStream.value = null
                    Uri.fromFile(File(localPath))
                } else {
                    // 2. Resolve stream via new StreamExtractor
                    Log.d(TAG, "[PLAYER] Resolving audio stream via StreamExtractor for videoId: ${song.id}")
                    val streamResult = streamExtractor.getAudioStream(song.id, forceRefresh = forceRefresh)
                    if (streamResult.isFailure) {
                        Log.e(TAG, "[PLAYER] Stream extraction failed for ${song.title}: ${streamResult.exceptionOrNull()?.message}")
                        _isLoadingStream.value = false
                        _playerStatus.value = PlayerStatus.IDLE
                        return@launch
                    }

                    val stream = streamResult.getOrThrow()
                    if (!stream.isValid()) {
                        Log.e(TAG, "[PLAYER] Extracted stream is invalid or expired for ${song.title}")
                        _isLoadingStream.value = false
                        _playerStatus.value = PlayerStatus.IDLE
                        return@launch
                    }

                    _currentStream.value = stream
                    Log.d(TAG, "[PLAYER] Starting Media3 with valid stream (bitrate=${stream.bitrate}, itag=${stream.itag})")
                    Uri.parse(stream.url)
                }

                _isLoadingStream.value = false

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(if (song.thumbnailUrl.isNotEmpty()) Uri.parse(song.thumbnailUrl) else null)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setUri(mediaUri)
                    .setMediaMetadata(mediaMetadata)
                    .build()

                withContext(Dispatchers.Main) {
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    if (seekPositionMs > 0) {
                        exoPlayer.seekTo(seekPositionMs)
                    }
                    exoPlayer.play()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[PLAYER] Exception during stream playback: ${e.message}")
                _isLoadingStream.value = false
                _playerStatus.value = PlayerStatus.IDLE
            }
        }
    }

    fun playPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE && _currentSong.value != null) {
                playSongInternal(_currentSong.value!!, forceRefresh = false)
            } else {
                exoPlayer.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _currentPosition.value = positionMs
    }

    fun playNext() {
        val list = _queue.value
        if (list.isEmpty()) return

        if (_repeatMode.value == RepeatMode.ONE && _currentSong.value != null) {
            seekTo(0)
            exoPlayer.play()
            return
        }

        val nextIndex = if (_isShuffle.value) {
            val unplayed = list.indices.filter { it != _currentIndex.value }
            if (unplayed.isNotEmpty()) unplayed.random() else 0
        } else {
            _currentIndex.value + 1
        }

        if (nextIndex in list.indices) {
            _currentIndex.value = nextIndex
            streamRetryCount = 0
            playSongInternal(list[nextIndex])
        } else if (_repeatMode.value == RepeatMode.ALL && list.isNotEmpty()) {
            _currentIndex.value = 0
            streamRetryCount = 0
            playSongInternal(list[0])
        }
    }

    fun playPrevious() {
        val list = _queue.value
        if (list.isEmpty()) return

        if (exoPlayer.currentPosition > 3000) {
            seekTo(0)
            return
        }

        val prevIndex = _currentIndex.value - 1
        if (prevIndex in list.indices) {
            _currentIndex.value = prevIndex
            streamRetryCount = 0
            playSongInternal(list[prevIndex])
        } else if (list.isNotEmpty()) {
            seekTo(0)
        }
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun addToQueueNext(song: Song) {
        val list = _queue.value.toMutableList()
        val insertIndex = (_currentIndex.value + 1).coerceIn(0, list.size)
        list.add(insertIndex, song)
        _queue.value = list
    }

    fun addToQueueEnd(song: Song) {
        val list = _queue.value.toMutableList()
        list.add(song)
        _queue.value = list
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _queue.value = list
            if (index < _currentIndex.value) {
                _currentIndex.value -= 1
            } else if (index == _currentIndex.value) {
                if (list.isNotEmpty()) {
                    val next = index.coerceIn(0, list.size - 1)
                    _currentIndex.value = next
                    streamRetryCount = 0
                    playSongInternal(list[next])
                } else {
                    _currentIndex.value = -1
                    _currentSong.value = null
                    exoPlayer.stop()
                }
            }
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val list = _queue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _queue.value = list
            if (_currentIndex.value == fromIndex) {
                _currentIndex.value = toIndex
            } else if (fromIndex < _currentIndex.value && toIndex >= _currentIndex.value) {
                _currentIndex.value -= 1
            } else if (fromIndex > _currentIndex.value && toIndex <= _currentIndex.value) {
                _currentIndex.value += 1
            }
        }
    }

    fun clearQueue() {
        val current = _currentSong.value
        if (current != null) {
            _queue.value = listOf(current)
            _currentIndex.value = 0
        } else {
            _queue.value = emptyList()
            _currentIndex.value = -1
        }
    }

    // Player.Listener Callbacks
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
        if (isPlaying) {
            streamRetryCount = 0 // Reset retry count on successful active playback
            recordHistoryIfNeeded()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> _playerStatus.value = PlayerStatus.IDLE
            Player.STATE_BUFFERING -> _playerStatus.value = PlayerStatus.BUFFERING
            Player.STATE_READY -> {
                _playerStatus.value = PlayerStatus.READY
                val dur = exoPlayer.duration
                if (dur > 0) {
                    _duration.value = dur
                }
                if (exoPlayer.isPlaying) {
                    recordHistoryIfNeeded()
                }
            }
            Player.STATE_ENDED -> {
                _playerStatus.value = PlayerStatus.ENDED
                playNext()
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "[PLAYER] Playback error occurred: ${error.errorCodeName} - ${error.message}")
        val song = _currentSong.value

        // Check if error is recoverable (HTTP 403/410 expired stream, network disconnect, etc.)
        val isRecoverable = error.errorCode in listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        ) || (error.message?.contains("403") == true) || (error.message?.contains("410") == true)

        if (song != null && isRecoverable && streamRetryCount < MAX_STREAM_RETRIES) {
            streamRetryCount++
            Log.w(TAG, "[PLAYER] Recoverable error detected, auto-refreshing stream URL (attempt $streamRetryCount of $MAX_STREAM_RETRIES)")
            val lastPos = _currentPosition.value
            playSongInternal(song, forceRefresh = true, seekPositionMs = lastPos)
        } else {
            _playerStatus.value = PlayerStatus.IDLE
            _isLoadingStream.value = false
        }
    }

    private fun recordHistoryIfNeeded() {
        val song = _currentSong.value ?: return
        if (lastRecordedSongId != song.id) {
            lastRecordedSongId = song.id
            scope.launch(Dispatchers.IO) {
                database.historyDao().insert(
                    HistoryEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationSeconds = song.durationSeconds,
                        durationFormatted = song.durationFormatted,
                        thumbnailUrl = song.thumbnailUrl,
                        playedAt = System.currentTimeMillis()
                    )
                )
                Log.d(TAG, "[DATABASE] Recorded history for: ${song.title}")
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        exoPlayer.release()
    }
}
