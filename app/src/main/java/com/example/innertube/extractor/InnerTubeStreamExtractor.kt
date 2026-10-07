package com.example.innertube.extractor

import android.net.Uri
import android.util.Log
import com.example.innertube.cipher.CipherDeobfuscator
import com.example.innertube.models.AudioStream
import com.example.innertube.potoken.PoTokenProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class InnerTubeStreamExtractor(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val poTokenProvider: PoTokenProvider = PoTokenProvider(okHttpClient),
    private val cipherDeobfuscator: CipherDeobfuscator = CipherDeobfuscator(okHttpClient)
) : StreamExtractor {

    companion object {
        private const val TAG = "InnerTubeExtractor"
        private const val YT_PLAYER_API = "https://www.youtube.com/youtubei/v1/player"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacy.com.de",
            "https://pipedapi.leptons.xyz"
        )
    }

    private val streamCache = ConcurrentHashMap<String, AudioStream>()
    private val activeRequests = ConcurrentHashMap<String, Deferred<Result<AudioStream>>>()
    private val mutex = Mutex()

    override suspend fun getAudioStream(
        videoId: String,
        forceRefresh: Boolean
    ): Result<AudioStream> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid empty videoId"))
        }

        // 1. Check in-memory cache if not forcing refresh
        if (!forceRefresh) {
            val cached = streamCache[videoId]
            if (cached != null && cached.isValid()) {
                Log.d(TAG, "[STREAM] Using valid cached audio stream for $videoId (bitrate=${cached.bitrate})")
                return@withContext Result.success(cached)
            }
        }

        // 2. Request deduplication: join active deferred request if one is in flight
        val existingDeferred = activeRequests[videoId]
        if (existingDeferred != null && existingDeferred.isActive) {
            Log.d(TAG, "[STREAM] Joining in-flight extraction request for $videoId")
            return@withContext existingDeferred.await()
        }

        // 3. Initiate new deferred extraction
        val deferred = async {
            val result = executeMultiClientExtraction(videoId)
            result.onSuccess { stream ->
                streamCache[videoId] = stream
            }
            result
        }

        activeRequests[videoId] = deferred
        try {
            deferred.await()
        } finally {
            activeRequests.remove(videoId)
        }
    }

    private suspend fun executeMultiClientExtraction(videoId: String): Result<AudioStream> {
        val visitorData = poTokenProvider.getVisitorData()
        Log.d(TAG, "[INNER_TUBE] Requesting player extraction for $videoId")

        // Strategy 1: ANDROID_VR Client (High direct URL availability)
        val androidVrStream = extractViaClient(
            videoId = videoId,
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            visitorData = visitorData,
            extraClientConfig = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "osName" to "Android",
                "osVersion" to "14"
            )
        )
        if (androidVrStream != null && androidVrStream.isValid()) {
            Log.d(TAG, "[EXTRACTION] Stream resolved via ANDROID_VR client (bitrate=${androidVrStream.bitrate})")
            return Result.success(androidVrStream)
        }

        // Strategy 2: IOS Client
        val iosStream = extractViaClient(
            videoId = videoId,
            clientName = "IOS",
            clientVersion = "19.45.4",
            visitorData = visitorData,
            extraClientConfig = mapOf(
                "deviceModel" to "iPhone16,2",
                "userAgent" to "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
            )
        )
        if (iosStream != null && iosStream.isValid()) {
            Log.d(TAG, "[EXTRACTION] Stream resolved via IOS client (bitrate=${iosStream.bitrate})")
            return Result.success(iosStream)
        }

        // Strategy 3: WEB_REMIX / ANDROID Client with Signature Cipher Deobfuscation
        val webStream = extractViaClient(
            videoId = videoId,
            clientName = "WEB_REMIX",
            clientVersion = "1.20241104.01.00",
            visitorData = visitorData
        )
        if (webStream != null && webStream.isValid()) {
            Log.d(TAG, "[EXTRACTION] Stream resolved via WEB_REMIX client (bitrate=${webStream.bitrate})")
            return Result.success(webStream)
        }

        // Strategy 4: TVHTML5 Embedded Client
        val tvStream = extractViaClient(
            videoId = videoId,
            clientName = "TVHTML5_SIMPLY_EMBEDDED",
            clientVersion = "2.0",
            visitorData = visitorData
        )
        if (tvStream != null && tvStream.isValid()) {
            Log.d(TAG, "[EXTRACTION] Stream resolved via TVHTML5 client (bitrate=${tvStream.bitrate})")
            return Result.success(tvStream)
        }

        // Strategy 5: Decentralized Piped Fallback (Only if direct extraction fails)
        val pipedStream = extractViaPipedFallback(videoId)
        if (pipedStream != null && pipedStream.isValid()) {
            Log.d(TAG, "[EXTRACTION] Stream resolved via decentralized fallback (bitrate=${pipedStream.bitrate})")
            return Result.success(pipedStream)
        }

        Log.e(TAG, "[ERROR] All stream extraction strategies failed for $videoId")
        return Result.failure(IllegalStateException("Unable to resolve playable audio stream for $videoId"))
    }

    private suspend fun extractViaClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        visitorData: String,
        extraClientConfig: Map<String, String> = emptyMap()
    ): AudioStream? {
        return try {
            val clientJson = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", "US")
                if (visitorData.isNotEmpty()) {
                    put("visitorData", visitorData)
                }
                for ((key, value) in extraClientConfig) {
                    put(key, value)
                }
            }

            val bodyJson = JSONObject().apply {
                put("context", JSONObject().put("client", clientJson))
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val requestBuilder = Request.Builder()
                .url(YT_PLAYER_API)
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))

            if (extraClientConfig.containsKey("userAgent")) {
                requestBuilder.header("User-Agent", extraClientConfig["userAgent"]!!)
            } else {
                requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) return null

            val resBody = response.body?.string() ?: return null
            parsePlayerResponse(JSONObject(resBody))
        } catch (e: Exception) {
            Log.w(TAG, "[INNER_TUBE] Client $clientName failed: ${e.message}")
            null
        }
    }

    private suspend fun parsePlayerResponse(json: JSONObject): AudioStream? {
        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status")
        if (status != null && status != "OK") {
            val reason = playabilityStatus.optString("reason", "Not playable")
            Log.w(TAG, "[INNER_TUBE] PlayabilityStatus not OK: $status ($reason)")
        }

        val streamingData = json.optJSONObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

        var bestStream: AudioStream? = null
        var maxBitrate = 0

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (!mimeType.startsWith("audio/")) continue

            val bitrate = format.optInt("bitrate", 0)
            val contentLength = format.optLong("contentLength", 0L)
            val quality = format.optString("audioQuality", "AUDIO_QUALITY_MEDIUM")
            val itag = format.optInt("itag", 0)

            // Direct URL or Signature Cipher resolution
            val map = mutableMapOf<String, String>()
            if (format.has("url")) {
                map["url"] = format.getString("url")
            }
            if (format.has("signatureCipher")) {
                map["signatureCipher"] = format.getString("signatureCipher")
            } else if (format.has("cipher")) {
                map["cipher"] = format.getString("cipher")
            }

            val resolvedUrl = cipherDeobfuscator.resolveStreamUrl(map) ?: continue
            if (resolvedUrl.isBlank()) continue

            val expirationTimestamp = extractExpirationFromUrl(resolvedUrl)

            if (bitrate > maxBitrate) {
                maxBitrate = bitrate
                bestStream = AudioStream(
                    url = resolvedUrl,
                    mimeType = mimeType,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    codec = mimeType.substringAfter("codecs=\"").substringBefore("\""),
                    quality = quality,
                    expirationTimestamp = expirationTimestamp,
                    itag = itag
                )
            }
        }

        return bestStream
    }

    private fun extractExpirationFromUrl(url: String): Long {
        return try {
            val uri = Uri.parse(url)
            val expireSecStr = uri.getQueryParameter("expire")
            if (!expireSecStr.isNullOrEmpty()) {
                val expireSec = expireSecStr.toLongOrNull() ?: 0L
                if (expireSec > 0) {
                    expireSec * 1000L
                } else {
                    System.currentTimeMillis() + (6 * 3600 * 1000L)
                }
            } else {
                System.currentTimeMillis() + (6 * 3600 * 1000L)
            }
        } catch (e: Exception) {
            System.currentTimeMillis() + (6 * 3600 * 1000L)
        }
    }

    private fun extractViaPipedFallback(videoId: String): AudioStream? {
        for (instance in PIPED_INSTANCES) {
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

                    var bestStream: AudioStream? = null
                    var maxBitrate = 0

                    for (i in 0 until audioStreams.length()) {
                        val stream = audioStreams.optJSONObject(i) ?: continue
                        val url = stream.optString("url")
                        val bitrate = stream.optInt("bitrate", 0)
                        val mimeType = stream.optString("mimeType", "audio/mp4")

                        if (url.isNotEmpty() && bitrate > maxBitrate) {
                            maxBitrate = bitrate
                            bestStream = AudioStream(
                                url = url,
                                mimeType = mimeType,
                                bitrate = bitrate,
                                expirationTimestamp = System.currentTimeMillis() + (4 * 3600 * 1000L)
                            )
                        }
                    }
                    if (bestStream != null) return bestStream
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        return null
    }

    fun clearCache() {
        streamCache.clear()
    }
}
