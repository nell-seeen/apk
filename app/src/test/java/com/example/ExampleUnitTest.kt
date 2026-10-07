package com.example

import com.example.data.network.LyricsClient
import com.example.innertube.cipher.CipherDeobfuscator
import com.example.innertube.cipher.CipherOp
import com.example.innertube.cipher.CipherOpType
import com.example.innertube.models.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testLrcParsing() {
        val lyricsClient = LyricsClient()
        val sampleLrc = """
            [00:12.50]Hello world
            [00:18.20]Music without login
            [01:05.00]MetroTune rocks
        """.trimIndent()

        val parsed = lyricsClient.parseLrcString(sampleLrc)
        assertEquals(3, parsed.size)
        assertEquals(12500L, parsed[0].timeMs)
        assertEquals("Hello world", parsed[0].text)
        assertEquals(18200L, parsed[1].timeMs)
        assertEquals("Music without login", parsed[1].text)
        assertEquals(65000L, parsed[2].timeMs)
        assertEquals("MetroTune rocks", parsed[2].text)
    }

    @Test
    fun testSongModelEffectiveUrl() {
        val onlineSong = com.example.data.model.Song(
            id = "test123",
            title = "Test Song",
            artist = "Artist",
            streamUrl = "https://audio.stream/123"
        )
        assertEquals("https://audio.stream/123", onlineSong.getEffectiveUrl())

        val offlineSong = onlineSong.copy(
            isDownloaded = true,
            localFilePath = "/data/user/0/app/files/downloads/test123.m4a"
        )
        assertEquals("/data/user/0/app/files/downloads/test123.m4a", offlineSong.getEffectiveUrl())
    }

    @Test
    fun testCipherOperations() {
        val deobfuscator = CipherDeobfuscator()

        // Test Reverse
        val revOps = listOf(CipherOp(CipherOpType.REVERSE, 0))
        assertEquals("cba", deobfuscator.applyOperations("abc", revOps))

        // Test Splice
        val spliceOps = listOf(CipherOp(CipherOpType.SPLICE, 2))
        assertEquals("cde", deobfuscator.applyOperations("abcde", spliceOps))

        // Test Swap
        val swapOps = listOf(CipherOp(CipherOpType.SWAP, 3))
        assertEquals("dbca", deobfuscator.applyOperations("abcd", swapOps))

        // Test Pipeline: reverse -> swap -> splice
        val pipelineOps = listOf(
            CipherOp(CipherOpType.REVERSE, 0), // "edcba"
            CipherOp(CipherOpType.SWAP, 2),    // swap index 0 and 2: "cdeba"
            CipherOp(CipherOpType.SPLICE, 1)   // splice 1: "deba"
        )
        assertEquals("deba", deobfuscator.applyOperations("abcde", pipelineOps))
    }

    @Test
    fun testAudioStreamValidation() {
        val validStream = AudioStream(
            url = "https://rr---.googlevideo.com/videoplayback?expire=9999999999",
            mimeType = "audio/mp4",
            bitrate = 128000,
            expirationTimestamp = System.currentTimeMillis() + 3600_000L
        )
        assertTrue(validStream.isValid())
        assertFalse(validStream.isExpired())

        val expiredStream = AudioStream(
            url = "https://rr---.googlevideo.com/videoplayback",
            mimeType = "audio/mp4",
            bitrate = 128000,
            expirationTimestamp = System.currentTimeMillis() - 1000L
        )
        assertFalse(expiredStream.isValid())
        assertTrue(expiredStream.isExpired())

        val invalidSchemeStream = AudioStream(
            url = "ftp://invalid.url",
            mimeType = "audio/mp4",
            bitrate = 128000
        )
        assertFalse(invalidSchemeStream.isValid())
    }
}
