package com.example.data.model

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int = 0,
    val durationFormatted: String = "0:00",
    val thumbnailUrl: String = "",
    val streamUrl: String? = null,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null
) {
    fun getEffectiveUrl(): String? = localFilePath ?: streamUrl
}

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class Lyrics(
    val songTitle: String,
    val artistName: String,
    val plainLyrics: String? = null,
    val syncedLyrics: List<LyricLine> = emptyList(),
    val isSynced: Boolean = false
)

data class Artist(
    val id: String,
    val name: String,
    val thumbnailUrl: String = "",
    val subscribers: String? = null,
    val description: String? = null,
    val popularSongs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val singles: List<Album> = emptyList()
)

data class Album(
    val id: String,
    val title: String,
    val artist: String = "",
    val thumbnailUrl: String = "",
    val year: String? = null,
    val trackCount: Int = 0,
    val tracks: List<Song> = emptyList()
)

data class PlaylistSummary(
    val id: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String = "",
    val trackCount: Int = 0,
    val tracks: List<Song> = emptyList()
)

data class SearchResult(
    val songs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList()
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadItem(
    val song: Song,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
)
