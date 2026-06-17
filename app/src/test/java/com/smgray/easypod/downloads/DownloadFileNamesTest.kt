package com.smgray.easypod.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFileNamesTest {
    @Test
    fun keepsAValidUrlExtension() {
        assertEquals(
            "episode-1.mp3",
            DownloadFileNames.forEpisode(
                "episode-1",
                "https://example.com/audio/show.MP3?token=abc",
                "audio/mpeg",
            ),
        )
    }

    @Test
    fun fallsBackToMimeTypeAndSanitizesId() {
        assertEquals(
            "episode_bad.m4a",
            DownloadFileNames.forEpisode(
                "episode/bad",
                "https://example.com/audio",
                "audio/mp4",
            ),
        )
    }
}

