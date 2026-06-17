package com.smgray.easypod.media

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeMediaClassifierTest {
    @Test
    fun classifiesFromMimeType() {
        assertEquals(
            EpisodeMediaType.Audio,
            EpisodeMediaClassifier.classify("audio/mpeg", null),
        )
        assertEquals(
            EpisodeMediaType.Video,
            EpisodeMediaClassifier.classify("video/mp4; charset=utf-8", null),
        )
        assertEquals(
            EpisodeMediaType.Image,
            EpisodeMediaClassifier.classify("image/jpeg", null),
        )
    }

    @Test
    fun fallsBackToMediaUrlExtension() {
        assertEquals(
            EpisodeMediaType.Video,
            EpisodeMediaClassifier.classify(null, "https://example.com/show.m4v?token=1"),
        )
        assertEquals(
            EpisodeMediaType.Audio,
            EpisodeMediaClassifier.classify(null, "https://example.com/show.mp3"),
        )
    }
}
