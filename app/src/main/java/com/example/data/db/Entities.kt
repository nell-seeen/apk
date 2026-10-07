package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.Song

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toSong(): Song = Song(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = durationSeconds,
        durationFormatted = durationFormatted,
        thumbnailUrl = thumbnailUrl
    )
}

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val playedAt: Long = System.currentTimeMillis()
) {
    fun toSong(): Song = Song(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = durationSeconds,
        durationFormatted = durationFormatted,
        thumbnailUrl = thumbnailUrl
    )
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val filePath: String,
    val fileSize: Long,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    fun toSong(): Song = Song(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = durationSeconds,
        durationFormatted = durationFormatted,
        thumbnailUrl = thumbnailUrl,
        isDownloaded = true,
        localFilePath = filePath
    )
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    indices = [Index(value = ["playlistId", "songId"], unique = true)]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toSong(): Song = Song(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = durationSeconds,
        durationFormatted = durationFormatted,
        thumbnailUrl = thumbnailUrl
    )
}
