package com.example

import com.example.data.network.LyricsClient
import org.junit.Assert.assertEquals
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
}
