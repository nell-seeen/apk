package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MetroTuneApp
import com.example.data.db.*
import com.example.data.model.*
import com.example.data.network.SearchFilter
import com.example.player.PlayerStatus
import com.example.player.RepeatMode
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class LyricsUiState {
    object Idle : LyricsUiState()
    object Loading : LyricsUiState()
    data class Success(val lyrics: Lyrics, val activeLineIndex: Int = -1) : LyricsUiState()
    object Unavailable : LyricsUiState()
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val app = MetroTuneApp.get(application)
    private val playerManager = app.playerManager
    private val database = app.database
    private val innerTubeClient = app.innerTubeClient
    private val lyricsClient = app.lyricsClient
    val downloadManager = app.downloadManager

    // Player State flows from PlayerManager
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val playerStatus: StateFlow<PlayerStatus> = playerManager.playerStatus
    val currentPosition: StateFlow<Long> = playerManager.currentPosition
    val duration: StateFlow<Long> = playerManager.duration
    val queue: StateFlow<List<Song>> = playerManager.queue
    val currentIndex: StateFlow<Int> = playerManager.currentIndex
    val isShuffle: StateFlow<Boolean> = playerManager.isShuffle
    val repeatMode: StateFlow<RepeatMode> = playerManager.repeatMode
    val isLoadingStream: StateFlow<Boolean> = playerManager.isLoadingStream

    // Search & Browse state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchFilter = MutableStateFlow(SearchFilter.ALL)
    val searchFilter: StateFlow<SearchFilter> = _searchFilter.asStateFlow()

    private val _searchResult = MutableStateFlow(SearchResult())
    val searchResult: StateFlow<SearchResult> = _searchResult.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Home / Quick Picks state
    private val _quickPicks = MutableStateFlow<List<Song>>(emptyList())
    val quickPicks: StateFlow<List<Song>> = _quickPicks.asStateFlow()

    private val _isLoadingHome = MutableStateFlow(false)
    val isLoadingHome: StateFlow<Boolean> = _isLoadingHome.asStateFlow()

    // Lyrics state
    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
    private var lyricsJob: Job? = null

    // Room Database Observables
    val favorites: StateFlow<List<FavoriteEntity>> = database.favoriteDao()
        .getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = database.historyDao()
        .getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = database.downloadDao()
        .getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localPlaylists: StateFlow<List<PlaylistEntity>> = database.playlistDao()
        .getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Theme mode
    private val _themeMode = MutableStateFlow(ThemeMode.AMOLED)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        loadQuickPicks()

        // Sync lyrics with current song and current position
        viewModelScope.launch {
            currentSong.collectLatest { song ->
                if (song != null) {
                    loadLyricsForSong(song)
                } else {
                    _lyricsState.value = LyricsUiState.Idle
                }
            }
        }

        // Active line tracker in synced lyrics
        viewModelScope.launch {
            combine(currentPosition, _lyricsState) { pos, state ->
                Pair(pos, state)
            }.collect { (pos, state) ->
                if (state is LyricsUiState.Success && state.lyrics.isSynced) {
                    val lines = state.lyrics.syncedLyrics
                    var activeIndex = -1
                    for (i in lines.indices) {
                        if (lines[i].timeMs <= pos) {
                            activeIndex = i
                        } else {
                            break
                        }
                    }
                    if (activeIndex != state.activeLineIndex) {
                        _lyricsState.value = state.copy(activeLineIndex = activeIndex)
                    }
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onSearchFilterChange(filter: SearchFilter) {
        _searchFilter.value = filter
        if (_searchQuery.value.trim().isNotEmpty()) {
            executeSearch(_searchQuery.value.trim(), filter)
        }
    }

    fun executeSearch(query: String = _searchQuery.value, filter: SearchFilter = _searchFilter.value) {
        if (query.isBlank()) return
        _isSearching.value = true
        viewModelScope.launch {
            try {
                val result = innerTubeClient.search(query, filter)
                _searchResult.value = result
            } catch (e: Exception) {
                Log.e("MusicViewModel", "[SEARCH] Search error: ${e.message}")
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun loadQuickPicks() {
        _isLoadingHome.value = true
        viewModelScope.launch {
            try {
                val songs = innerTubeClient.getQuickPicks()
                _quickPicks.value = songs
            } catch (e: Exception) {
                Log.e("MusicViewModel", "[HOME] Quick picks error: ${e.message}")
            } finally {
                _isLoadingHome.value = false
            }
        }
    }

    private fun loadLyricsForSong(song: Song) {
        lyricsJob?.cancel()
        _lyricsState.value = LyricsUiState.Loading
        lyricsJob = viewModelScope.launch {
            try {
                val lyrics = lyricsClient.getLyrics(song)
                if (lyrics != null && (!lyrics.plainLyrics.isNullOrEmpty() || lyrics.syncedLyrics.isNotEmpty())) {
                    _lyricsState.value = LyricsUiState.Success(lyrics = lyrics)
                } else {
                    _lyricsState.value = LyricsUiState.Unavailable
                }
            } catch (e: Exception) {
                _lyricsState.value = LyricsUiState.Unavailable
            }
        }
    }

    // Playback Controls
    fun playSong(song: Song, queue: List<Song> = listOf(song), index: Int = 0) {
        playerManager.playSong(song, queue, index)
    }

    fun playQueueIndex(index: Int) {
        playerManager.playQueueIndex(index)
    }

    fun togglePlayPause() {
        playerManager.playPause()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
    }

    fun playNext() {
        playerManager.playNext()
    }

    fun playPrevious() {
        playerManager.playPrevious()
    }

    fun toggleShuffle() {
        playerManager.toggleShuffle()
    }

    fun toggleRepeat() {
        playerManager.toggleRepeat()
    }

    fun addToQueueNext(song: Song) {
        playerManager.addToQueueNext(song)
    }

    fun addToQueueEnd(song: Song) {
        playerManager.addToQueueEnd(song)
    }

    fun removeFromQueue(index: Int) {
        playerManager.removeFromQueue(index)
    }

    fun reorderQueue(from: Int, to: Int) {
        playerManager.reorderQueue(from, to)
    }

    fun clearQueue() {
        playerManager.clearQueue()
    }

    // Favorites
    fun isFavorite(songId: String): Flow<Boolean> {
        return database.favoriteDao().isFavorite(songId)
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            val isFav = database.favoriteDao().isFavoriteSync(song.id)
            if (isFav) {
                database.favoriteDao().delete(song.id)
            } else {
                database.favoriteDao().insert(
                    FavoriteEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationSeconds = song.durationSeconds,
                        durationFormatted = song.durationFormatted,
                        thumbnailUrl = song.thumbnailUrl
                    )
                )
            }
        }
    }

    // Downloads
    fun startDownload(song: Song) {
        downloadManager.startDownload(song)
    }

    fun cancelDownload(songId: String) {
        downloadManager.cancelDownload(songId)
    }

    fun deleteDownload(songId: String) {
        downloadManager.deleteDownload(songId)
    }

    fun isDownloaded(songId: String): Boolean {
        return downloadManager.isDownloaded(songId)
    }

    // Local Playlists
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            database.playlistDao().insertPlaylist(PlaylistEntity(name = name.trim()))
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            database.playlistDao().renamePlaylist(playlistId, newName.trim())
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            database.playlistDao().deleteTracksForPlaylist(playlistId)
            database.playlistDao().deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            database.playlistDao().addTrack(
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationSeconds = song.durationSeconds,
                    durationFormatted = song.durationFormatted,
                    thumbnailUrl = song.thumbnailUrl
                )
            )
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            database.playlistDao().removeTrack(playlistId, songId)
        }
    }

    fun getPlaylistTracks(playlistId: Long): Flow<List<Song>> {
        return database.playlistDao().getTracksForPlaylist(playlistId).map { list ->
            list.map { it.toSong() }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            database.historyDao().clearHistory()
        }
    }

    suspend fun getAlbumOrPlaylistSongs(browseId: String): List<Song> {
        return innerTubeClient.getAlbumOrPlaylistTracks(browseId)
    }
}
