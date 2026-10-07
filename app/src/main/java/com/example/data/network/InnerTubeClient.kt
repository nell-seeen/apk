package com.example.data.network

import com.example.data.model.*
import com.example.innertube.browse.InnerTubeMusic
import com.example.innertube.extractor.InnerTubeStreamExtractor
import com.example.innertube.extractor.StreamExtractor
import com.example.innertube.models.AudioStream
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class SearchFilter {
    ALL, SONGS, ARTISTS, ALBUMS, PLAYLISTS
}

/**
 * High-level client delegating to the specialized InnerTubeMusic browse engine
 * and the InnerTubeStreamExtractor.
 */
class InnerTubeClient(
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    val streamExtractor: StreamExtractor = InnerTubeStreamExtractor(okHttpClient),
    val innerTubeMusic: InnerTubeMusic = InnerTubeMusic(okHttpClient)
) {
    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResult {
        return innerTubeMusic.search(query, filter)
    }

    suspend fun getStreamUrl(videoId: String): String? {
        val streamResult = streamExtractor.getAudioStream(videoId)
        return streamResult.getOrNull()?.url
    }

    suspend fun getAudioStream(videoId: String, forceRefresh: Boolean = false): Result<AudioStream> {
        return streamExtractor.getAudioStream(videoId, forceRefresh)
    }

    suspend fun getQuickPicks(): List<Song> {
        return innerTubeMusic.getHomePicks()
    }

    suspend fun getAlbumOrPlaylistTracks(browseId: String): List<Song> {
        return innerTubeMusic.getAlbumOrPlaylistTracks(browseId)
    }
}
