package com.example.data.network

import android.util.Log
import com.example.data.model.LyricLine
import com.example.data.model.Lyrics
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LyricsClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "LyricsClient"
        private const val LRCLIB_URL = "https://lrclib.net/api"
        private val LRC_REGEX = Pattern.compile("^\\[(\\d{2}):(\\d{2}(?:\\.\\d+)?)\\](.*)$")
    }

    suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSongTitle(song.title)
        val cleanArtist = cleanArtistName(song.artist)

        Log.d(TAG, "[LYRICS] Fetching lyrics for '$cleanTitle' by '$cleanArtist'")

        // 1. Try exact match from LRCLIB
        val exact = fetchLrclibExact(cleanTitle, cleanArtist, song.durationSeconds)
        if (exact != null) {
            return@withContext exact
        }

        // 2. Try search on LRCLIB
        val searched = searchLrclib(cleanTitle, cleanArtist)
        if (searched != null) {
            return@withContext searched
        }

        Log.d(TAG, "[LYRICS] Lyrics unavailable for: ${song.title}")
        null
    }

    private fun fetchLrclibExact(title: String, artist: String, durationSec: Int): Lyrics? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = buildString {
                append("$LRCLIB_URL/get?track_name=$encodedTitle&artist_name=$encodedArtist")
                if (durationSec > 0) {
                    append("&duration=$durationSec")
                }
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "MetroTune-Android/1.0")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                parseLrclibResponse(JSONObject(body), title, artist)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LYRICS] Error in exact fetch: ${e.message}")
            null
        }
    }

    private fun searchLrclib(title: String, artist: String): Lyrics? {
        return try {
            val query = "$title $artist"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$LRCLIB_URL/search?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "MetroTune-Android/1.0")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)
                if (array.length() > 0) {
                    parseLrclibResponse(array.getJSONObject(0), title, artist)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "[LYRICS] Error in search fetch: ${e.message}")
            null
        }
    }

    private fun parseLrclibResponse(json: JSONObject, title: String, artist: String): Lyrics {
        val plainLyrics = json.optString("plainLyrics").takeIf { it.isNotEmpty() }
        val syncedLyricsRaw = json.optString("syncedLyrics").takeIf { it.isNotEmpty() }

        val lines = if (!syncedLyricsRaw.isNullOrEmpty()) {
            parseLrcString(syncedLyricsRaw)
        } else {
            emptyList()
        }

        return Lyrics(
            songTitle = title,
            artistName = artist,
            plainLyrics = plainLyrics,
            syncedLyrics = lines,
            isSynced = lines.isNotEmpty()
        )
    }

    fun parseLrcString(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val rawLines = lrc.split("\n")

        for (line in rawLines) {
            val trimmed = line.trim()
            val matcher = LRC_REGEX.matcher(trimmed)
            if (matcher.matches()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val secStr = matcher.group(2) ?: "0"
                val text = matcher.group(3)?.trim() ?: ""

                val secDouble = secStr.toDoubleOrNull() ?: 0.0
                val timeMs = (min * 60 * 1000) + (secDouble * 1000).toLong()

                result.add(LyricLine(timeMs, text))
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun cleanSongTitle(title: String): String {
        return title
            .replace(Regex("\\(.*?official.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[.*?official.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?music video.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[.*?music video.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?video.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[.*?video.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?audio.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[.*?audio.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?lyric.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[.*?lyric.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("feat\\..*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("ft\\..*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(Regex(" - Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
