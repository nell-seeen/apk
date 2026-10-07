package com.example.innertube.extractor

import com.example.innertube.models.AudioStream

interface StreamExtractor {
    suspend fun getAudioStream(videoId: String, forceRefresh: Boolean = false): Result<AudioStream>
}
