package com.example.innertube.potoken

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class PoTokenProvider(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "PoTokenProvider"
        private const val VISITOR_ENDPOINT = "https://www.youtube.com/youtubei/v1/visitor_id"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val CACHE_LIFETIME_MS = 12 * 60 * 60 * 1000L // 12 hours
    }

    private val mutex = Mutex()
    private var cachedVisitorData: String? = null
    private var lastFetchTimestamp: Long = 0L
    private val random = SecureRandom()

    suspend fun getVisitorData(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!cachedVisitorData.isNullOrEmpty() && (now - lastFetchTimestamp) < CACHE_LIFETIME_MS) {
                return@withLock cachedVisitorData!!
            }

            try {
                Log.d(TAG, "[PO_TOKEN] Requesting fresh visitor data from InnerTube")
                val requestContext = JSONObject().apply {
                    val client = JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241104.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    put("context", JSONObject().put("client", client))
                }

                val request = Request.Builder()
                    .url(VISITOR_ENDPOINT)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .post(requestContext.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val bodyStr = response.body?.string()
                if (response.isSuccessful && !bodyStr.isNullOrEmpty()) {
                    val json = JSONObject(bodyStr)
                    val visitorData = json.optJSONObject("responseContext")?.optString("visitorData")
                    if (!visitorData.isNullOrEmpty()) {
                        cachedVisitorData = visitorData
                        lastFetchTimestamp = now
                        Log.d(TAG, "[PO_TOKEN] Obtained visitorData: ${visitorData.take(12)}...")
                        return@withLock visitorData
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[PO_TOKEN] Failed to fetch visitorData: ${e.message}")
            }

            // Fallback to existing or synthetic token if network fails
            cachedVisitorData ?: "Cgt2U0h4ZDRYTG5VWSiA"
        }
    }

    fun generateClientNonce(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        return sb.toString()
    }
}
