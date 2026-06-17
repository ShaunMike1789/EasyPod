package com.smgray.easypod.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FeedUrlValidationTest {
    @Test
    fun addsHttpsSchemeAndNormalizesUrl() {
        assertEquals(
            "https://example.com/feed.xml",
            normalizePodcastFeedUrl(" example.com/feed.xml "),
        )
    }

    @Test
    fun acceptsHttpFeedUrlsForLegacyPodcastCompatibility() {
        assertEquals(
            "http://example.com/feed.xml",
            normalizePodcastFeedUrl("http://example.com/feed.xml"),
        )
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizePodcastFeedUrl("https://user:secret@example.com/feed.xml")
        }
    }
}
