package com.example.data.network

import android.util.Log
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class SearchFilter {
    ALL, SONGS, ARTISTS, ALBUMS, PLAYLISTS
}

class InnerTubeClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "InnerTubeClient"
        private const val YT_MUSIC_API = "https://music.youtube.com/youtubei/v1"
        private const val YT_PLAYER_API = "https://www.youtube.com/youtubei/v1"

        // Params filter codes for YouTube Music Search
        private const val FILTER_SONGS = "Eg-KAQwIABAAGAAgACgAMABqChAMEAEYBxAFEBE%3D"
        private const val FILTER_ARTISTS = "Eg-KAQwIABAAGAAgASgAMABqChAMEAEYBxAFEBE%3D"
        private const val FILTER_ALBUMS = "Eg-KAQwIABAAGAAgACgBMABqChAMEAEYBxAFEBE%3D"
        private const val FILTER_PLAYLISTS = "Eg-KAQwIABAAGAAgACgBMAJqChAMEAEYBxAFEBE%3D"

        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private fun getWebRemixContext(): JSONObject {
        val client = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20240101.01.00")
            put("hl", "en")
            put("gl", "US")
        }
        return JSONObject().apply {
            put("client", client)
        }
    }

    private fun getIosContext(): JSONObject {
        val client = JSONObject().apply {
            put("clientName", "IOS")
            put("clientVersion", "19.29.1")
            put("deviceModel", "iPhone16,2")
            put("userAgent", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)")
            put("hl", "en")
            put("gl", "US")
        }
        return JSONObject().apply {
            put("client", client)
        }
    }

    private fun getAndroidContext(): JSONObject {
        val client = JSONObject().apply {
            put("clientName", "ANDROID_MUSIC")
            put("clientVersion", "6.42.52")
            put("androidSdkVersion", 34)
            put("hl", "en")
            put("gl", "US")
        }
        return JSONObject().apply {
            put("client", client)
        }
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResult =
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("context", getWebRemixContext())
                    put("query", query)
                    when (filter) {
                        SearchFilter.SONGS -> put("params", FILTER_SONGS)
                        SearchFilter.ARTISTS -> put("params", FILTER_ARTISTS)
                        SearchFilter.ALBUMS -> put("params", FILTER_ALBUMS)
                        SearchFilter.PLAYLISTS -> put("params", FILTER_PLAYLISTS)
                        SearchFilter.ALL -> { /* no filter */ }
                    }
                }

                val request = Request.Builder()
                    .url("$YT_MUSIC_API/search")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Origin", "https://music.youtube.com")
                    .addHeader("Referer", "https://music.youtube.com/")
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseStr = response.body?.string() ?: return@withContext SearchResult()
                parseSearchResponse(JSONObject(responseStr), filter)
            } catch (e: Exception) {
                Log.e(TAG, "[SEARCH] Error searching for '$query': ${e.message}")
                SearchResult()
            }
        }

    private fun parseSearchResponse(root: JSONObject, filter: SearchFilter): SearchResult {
        val songs = mutableListOf<Song>()
        val artists = mutableListOf<Artist>()
        val albums = mutableListOf<Album>()
        val playlists = mutableListOf<PlaylistSummary>()

        val contents = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: JSONArray()

        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i) ?: continue
            val shelf = section.optJSONObject("musicShelfRenderer")
                ?: section.optJSONObject("musicCardShelfRenderer") ?: continue

            val shelfContents = shelf.optJSONArray("contents") ?: JSONArray()
            for (j in 0 until shelfContents.length()) {
                val itemObj = shelfContents.optJSONObject(j) ?: continue
                val item = itemObj.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                val navEndpoint = item.optJSONObject("navigationEndpoint")
                    ?: item.optJSONObject("overlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")

                val flexColumns = item.optJSONArray("flexColumns") ?: JSONArray()
                if (flexColumns.length() == 0) continue

                // Primary title
                val titleRun = flexColumns.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                val title = titleRun?.optString("text") ?: ""

                // Subtitle / metadata runs
                val subtitleRuns = if (flexColumns.length() > 1) {
                    flexColumns.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs") ?: JSONArray()
                } else JSONArray()

                val artistName = subtitleRuns.optJSONObject(0)?.optString("text") ?: "Unknown Artist"
                var albumName = ""
                var durationStr = "3:30"
                for (k in 1 until subtitleRuns.length()) {
                    val text = subtitleRuns.optJSONObject(k)?.optString("text") ?: ""
                    if (text.contains(":")) {
                        durationStr = text
                    } else if (text.length > 2 && text != "•" && text != " " && albumName.isEmpty()) {
                        albumName = text
                    }
                }

                // Thumbnail
                val thumbnails = item.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails") ?: JSONArray()
                val thumbUrl = if (thumbnails.length() > 0) {
                    thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url") ?: ""
                } else ""

                // VideoId or BrowseId
                val watchEndpoint = navEndpoint?.optJSONObject("watchEndpoint")
                val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
                val videoId = watchEndpoint?.optString("videoId")
                    ?: item.optJSONObject("playlistItemData")?.optString("videoId")
                    ?: ""
                val browseId = browseEndpoint?.optString("browseId") ?: ""

                val durationSec = parseDuration(durationStr)

                // Categorize based on endpoints or filter
                if (videoId.isNotEmpty()) {
                    songs.add(
                        Song(
                            id = videoId,
                            title = title,
                            artist = artistName,
                            album = albumName,
                            durationSeconds = durationSec,
                            durationFormatted = durationStr,
                            thumbnailUrl = cleanThumbnailUrl(thumbUrl)
                        )
                    )
                } else if (browseId.startsWith("UC") || browseId.startsWith("FEmusic_library_privately_owned_artist")) {
                    artists.add(
                        Artist(
                            id = browseId,
                            name = title,
                            thumbnailUrl = cleanThumbnailUrl(thumbUrl)
                        )
                    )
                } else if (browseId.startsWith("MPREb_") || browseId.startsWith("FEmusic_library_privately_owned_release")) {
                    albums.add(
                        Album(
                            id = browseId,
                            title = title,
                            artist = artistName,
                            thumbnailUrl = cleanThumbnailUrl(thumbUrl)
                        )
                    )
                } else if (browseId.startsWith("VL") || browseId.startsWith("RDAM") || browseId.startsWith("MPRE")) {
                    playlists.add(
                        PlaylistSummary(
                            id = browseId,
                            title = title,
                            author = artistName,
                            thumbnailUrl = cleanThumbnailUrl(thumbUrl)
                        )
                    )
                } else {
                    // Fallback to song if videoId exists anywhere in runs
                    if (filter == SearchFilter.SONGS && title.isNotEmpty()) {
                        songs.add(
                            Song(
                                id = browseId.ifEmpty { "song_$j" },
                                title = title,
                                artist = artistName,
                                album = albumName,
                                durationSeconds = durationSec,
                                durationFormatted = durationStr,
                                thumbnailUrl = cleanThumbnailUrl(thumbUrl)
                            )
                        )
                    }
                }
            }
        }

        return SearchResult(
            songs = songs.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id }
        )
    }

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isEmpty()) return@withContext null
        Log.d(TAG, "[PLAYER] Resolving audio stream for videoId: $videoId")

        // Strategy 1: YouTube Player API with IOS client (no signature cipher required)
        val iosStream = fetchStreamViaYoutubeIos(videoId)
        if (!iosStream.isNullOrEmpty()) {
            Log.d(TAG, "[PLAYER] Resolved stream via YouTube IOS: $videoId")
            return@withContext iosStream
        }

        // Strategy 2: YouTube Player API with ANDROID_VR / TVHTML5 client
        val tvStream = fetchStreamViaYoutubeTv(videoId)
        if (!tvStream.isNullOrEmpty()) {
            Log.d(TAG, "[PLAYER] Resolved stream via YouTube TV: $videoId")
            return@withContext tvStream
        }

        // Strategy 3: Piped / Invidious public streaming instances (fallback)
        val pipedStream = fetchStreamViaPiped(videoId)
        if (!pipedStream.isNullOrEmpty()) {
            Log.d(TAG, "[PLAYER] Resolved stream via Piped fallback: $videoId")
            return@withContext pipedStream
        }

        Log.e(TAG, "[PLAYER] Failed to resolve audio stream for videoId: $videoId")
        null
    }

    private fun fetchStreamViaYoutubeIos(videoId: String): String? {
        return try {
            val bodyJson = JSONObject().apply {
                put("context", getIosContext())
                put("videoId", videoId)
            }
            val request = Request.Builder()
                .url("$YT_PLAYER_API/player")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val resStr = response.body?.string() ?: return null
            extractAudioUrlFromStreamingData(JSONObject(resStr))
        } catch (e: Exception) {
            Log.e(TAG, "[PLAYER] iOS player fetch error: ${e.message}")
            null
        }
    }

    private fun fetchStreamViaYoutubeTv(videoId: String): String? {
        return try {
            val client = JSONObject().apply {
                put("clientName", "TVHTML5_SIMPLY_EMBEDDED")
                put("clientVersion", "2.0")
                put("hl", "en")
                put("gl", "US")
            }
            val bodyJson = JSONObject().apply {
                put("context", JSONObject().put("client", client))
                put("videoId", videoId)
            }
            val request = Request.Builder()
                .url("$YT_PLAYER_API/player")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val resStr = response.body?.string() ?: return null
            extractAudioUrlFromStreamingData(JSONObject(resStr))
        } catch (e: Exception) {
            Log.e(TAG, "[PLAYER] TV player fetch error: ${e.message}")
            null
        }
    }

    private fun extractAudioUrlFromStreamingData(root: JSONObject): String? {
        val streamingData = root.optJSONObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

        var bestAudioUrl: String? = null
        var highestBitrate = 0

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("audio/")) {
                val directUrl = format.optString("url")
                val bitrate = format.optInt("bitrate", 0)
                if (directUrl.isNotEmpty() && bitrate > highestBitrate) {
                    bestAudioUrl = directUrl
                    highestBitrate = bitrate
                }
            }
        }
        return bestAudioUrl
    }

    private fun fetchStreamViaPiped(videoId: String): String? {
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacy.com.de",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi.r4fo.com"
        )
        for (instance in pipedInstances) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .get()
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val audioStreams = json.optJSONArray("audioStreams") ?: continue
                    for (i in 0 until audioStreams.length()) {
                        val stream = audioStreams.optJSONObject(i) ?: continue
                        val url = stream.optString("url")
                        if (url.isNotEmpty()) {
                            return url
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next instance
            }
        }
        return null
    }

    suspend fun getQuickPicks(): List<Song> = withContext(Dispatchers.IO) {
        val search = search("Top Hits 2024 Popular Music", SearchFilter.SONGS)
        if (search.songs.isNotEmpty()) {
            return@withContext search.songs
        }
        // Fallback search
        search("Acoustic Chill Lo-Fi", SearchFilter.SONGS).songs
    }

    suspend fun getAlbumOrPlaylistTracks(browseId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("context", getWebRemixContext())
                put("browseId", browseId)
            }
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val resStr = response.body?.string() ?: return@withContext emptyList()
            parseTracksFromBrowse(JSONObject(resStr))
        } catch (e: Exception) {
            Log.e(TAG, "[NETWORK] Error loading tracks for $browseId: ${e.message}")
            emptyList()
        }
    }

    private fun parseTracksFromBrowse(root: JSONObject): List<Song> {
        val list = mutableListOf<Song>()
        val contents = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("secondaryContents")
            ?: root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

        fun searchInJson(obj: Any?) {
            when (obj) {
                is JSONObject -> {
                    if (obj.has("musicResponsiveListItemRenderer")) {
                        val item = obj.getJSONObject("musicResponsiveListItemRenderer")
                        val flexColumns = item.optJSONArray("flexColumns") ?: JSONArray()
                        if (flexColumns.length() > 0) {
                            val title = flexColumns.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text") ?: ""

                            val subtitleRuns = if (flexColumns.length() > 1) {
                                flexColumns.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs") ?: JSONArray()
                            } else JSONArray()

                            val artist = subtitleRuns.optJSONObject(0)?.optString("text") ?: ""
                            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
                                ?: item.optJSONObject("overlay")
                                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                    ?.optJSONObject("content")
                                    ?.optJSONObject("musicPlayButtonRenderer")
                                    ?.optJSONObject("playNavigationEndpoint")
                                    ?.optJSONObject("watchEndpoint")
                                    ?.optString("videoId") ?: ""

                            val thumb = item.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                                ?.optJSONObject(0)
                                ?.optString("url") ?: ""

                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                list.add(
                                    Song(
                                        id = videoId,
                                        title = title,
                                        artist = artist,
                                        thumbnailUrl = cleanThumbnailUrl(thumb)
                                    )
                                )
                            }
                        }
                    }
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        searchInJson(obj.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until obj.length()) {
                        searchInJson(obj.opt(i))
                    }
                }
            }
        }

        searchInJson(contents)
        return list.distinctBy { it.id }
    }

    private fun cleanThumbnailUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) return "https:$url"
        return url.replace(Regex("=w\\d+-h\\d+.*"), "=w500-h500-l90-rj")
    }

    private fun parseDuration(duration: String): Int {
        val parts = duration.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 180
        }
    }
}
