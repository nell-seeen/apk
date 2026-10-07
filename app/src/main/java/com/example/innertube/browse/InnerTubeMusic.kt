package com.example.innertube.browse

import android.util.Log
import com.example.data.model.*
import com.example.data.network.SearchFilter
import com.example.innertube.potoken.PoTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class InnerTubeMusic(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val poTokenProvider: PoTokenProvider = PoTokenProvider(okHttpClient)
) {
    companion object {
        private const val TAG = "InnerTubeMusic"
        private const val YT_MUSIC_API = "https://music.youtube.com/youtubei/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private const val FILTER_SONGS = "Eg-KAQwIABAAGAAgACgAMABqChAMEAEYBxAFEBE%3D"
        private const val FILTER_ARTISTS = "Eg-KAQwIABAAGAAgASgAMABqChAMEAEYBxAFEBE%3D"
        private const val FILTER_ALBUMS = "Eg-KAQwIABAAGAAgACgBMABqChAMEAEYBxAFEBE%3D"
        private const val FILTER_PLAYLISTS = "Eg-KAQwIABAAGAAgACgBMAJqChAMEAEYBxAFEBE%3D"
    }

    private suspend fun getRequestContext(): JSONObject {
        val visitorData = poTokenProvider.getVisitorData()
        val client = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20241104.01.00")
            put("hl", "en")
            put("gl", "US")
            if (visitorData.isNotEmpty()) {
                put("visitorData", visitorData)
            }
        }
        return JSONObject().apply {
            put("client", client)
        }
    }

    suspend fun getHomePicks(): List<Song> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[INNER_TUBE] Fetching real home recommendations (FEmusic_home)")
            val bodyJson = JSONObject().apply {
                put("context", getRequestContext())
                put("browseId", "FEmusic_home")
            }

            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            val parsedSongs = parseSongsFromBrowse(JSONObject(bodyStr))
            if (parsedSongs.isNotEmpty()) {
                Log.d(TAG, "[INNER_TUBE] Retrieved ${parsedSongs.size} real songs from FEmusic_home")
                return@withContext parsedSongs
            }

            // Fallback to FEmusic_explore if home is empty
            getExplorePicks()
        } catch (e: Exception) {
            Log.e(TAG, "[INNER_TUBE] Home picks fetch error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getExplorePicks(): List<Song> {
        return try {
            val bodyJson = JSONObject().apply {
                put("context", getRequestContext())
                put("browseId", "FEmusic_explore")
            }
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return emptyList()
            parseSongsFromBrowse(JSONObject(bodyStr))
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchResult()

        try {
            Log.d(TAG, "[SEARCH] Executing YouTube Music search for: $query (filter=$filter)")
            val bodyJson = JSONObject().apply {
                put("context", getRequestContext())
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
            val bodyStr = response.body?.string() ?: return@withContext SearchResult()
            parseSearchResponse(JSONObject(bodyStr))
        } catch (e: Exception) {
            Log.e(TAG, "[SEARCH] Error searching: ${e.message}")
            SearchResult()
        }
    }

    private fun parseSearchResponse(root: JSONObject): SearchResult {
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

                // Title
                val titleRun = flexColumns.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                val title = titleRun?.optString("text")?.trim() ?: ""
                if (title.isEmpty()) continue

                // Metadata runs
                val subtitleRuns = if (flexColumns.length() > 1) {
                    flexColumns.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs") ?: JSONArray()
                } else JSONArray()

                var artistName = "Unknown Artist"
                var albumName = ""
                var durationStr = ""

                for (k in 0 until subtitleRuns.length()) {
                    val run = subtitleRuns.optJSONObject(k) ?: continue
                    val text = run.optString("text").trim()
                    if (text == "•" || text == "|" || text.isEmpty()) continue

                    val pageType = run.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                        ?.optString("pageType")

                    if (text.contains(":") && durationStr.isEmpty()) {
                        durationStr = text
                    } else if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || (k == 0 && artistName == "Unknown Artist")) {
                        artistName = text
                    } else if (pageType == "MUSIC_PAGE_TYPE_ALBUM" || (albumName.isEmpty() && !text.contains(":"))) {
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

                // Video ID or Browse ID
                val watchEndpoint = navEndpoint?.optJSONObject("watchEndpoint")
                val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
                val videoId = watchEndpoint?.optString("videoId")
                    ?: item.optJSONObject("playlistItemData")?.optString("videoId")
                    ?: ""
                val browseId = browseEndpoint?.optString("browseId") ?: ""

                val durationSec = if (durationStr.isNotEmpty()) parseDuration(durationStr) else 0

                // ONLY create a Song if a real videoId is present (Never use fake IDs!)
                if (videoId.isNotEmpty()) {
                    songs.add(
                        Song(
                            id = videoId,
                            title = title,
                            artist = artistName,
                            album = albumName,
                            durationSeconds = durationSec,
                            durationFormatted = if (durationStr.isNotEmpty()) durationStr else "0:00",
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

    suspend fun getAlbumOrPlaylistTracks(browseId: String): List<Song> = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext emptyList()
        try {
            val bodyJson = JSONObject().apply {
                put("context", getRequestContext())
                put("browseId", browseId)
            }
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            parseSongsFromBrowse(JSONObject(bodyStr))
        } catch (e: Exception) {
            Log.e(TAG, "[INNER_TUBE] Error browsing tracks for $browseId: ${e.message}")
            emptyList()
        }
    }

    private fun parseSongsFromBrowse(root: JSONObject): List<Song> {
        val list = mutableListOf<Song>()

        fun scanJson(obj: Any?) {
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
                                ?.optString("text")?.trim() ?: ""

                            val subtitleRuns = if (flexColumns.length() > 1) {
                                flexColumns.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs") ?: JSONArray()
                            } else JSONArray()

                            var artist = ""
                            var album = ""
                            var durationStr = ""

                            for (k in 0 until subtitleRuns.length()) {
                                val run = subtitleRuns.optJSONObject(k) ?: continue
                                val text = run.optString("text").trim()
                                if (text == "•" || text == "|" || text.isEmpty()) continue

                                if (text.contains(":")) {
                                    durationStr = text
                                } else if (artist.isEmpty()) {
                                    artist = text
                                } else if (album.isEmpty()) {
                                    album = text
                                }
                            }

                            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
                                ?: item.optJSONObject("overlay")
                                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                    ?.optJSONObject("content")
                                    ?.optJSONObject("musicPlayButtonRenderer")
                                    ?.optJSONObject("playNavigationEndpoint")
                                    ?.optJSONObject("watchEndpoint")
                                    ?.optString("videoId")
                                ?: item.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("watchEndpoint")
                                    ?.optString("videoId")
                                ?: ""

                            val thumb = item.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                                ?.optJSONObject(0)
                                ?.optString("url") ?: ""

                            // Only add if there is a real, valid videoId
                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                list.add(
                                    Song(
                                        id = videoId,
                                        title = title,
                                        artist = artist.ifEmpty { "Unknown Artist" },
                                        album = album,
                                        durationSeconds = parseDuration(durationStr),
                                        durationFormatted = if (durationStr.isNotEmpty()) durationStr else "0:00",
                                        thumbnailUrl = cleanThumbnailUrl(thumb)
                                    )
                                )
                            }
                        }
                    }
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        scanJson(obj.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until obj.length()) {
                        scanJson(obj.opt(i))
                    }
                }
            }
        }

        scanJson(root)
        return list.distinctBy { it.id }
    }

    private fun cleanThumbnailUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) return "https:$url"
        return url.replace(Regex("=w\\d+-h\\d+.*"), "=w500-h500-l90-rj")
    }

    private fun parseDuration(duration: String): Int {
        if (duration.isBlank()) return 0
        val parts = duration.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }
}
