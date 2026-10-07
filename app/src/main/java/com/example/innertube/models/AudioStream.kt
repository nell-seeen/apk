package com.example.innertube.models

data class AudioStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long = 0L,
    val codec: String = "",
    val quality: String = "",
    val expirationTimestamp: Long = 0L,
    val itag: Int = 0
) {
    fun isExpired(): Boolean {
        if (expirationTimestamp <= 0) return false
        // Consider expired if within 60 seconds of expiration
        return System.currentTimeMillis() >= (expirationTimestamp - 60_000L)
    }

    fun isValid(): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (isExpired()) return false
        return true
    }
}
