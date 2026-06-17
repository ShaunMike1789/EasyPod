package com.smgray.easypod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingPodcastIntentsTest {
    @Test
    fun extractsHttpFeedUrlFromSharedText() {
        assertEquals(
            "https://example.com/show.xml",
            IncomingPodcastIntents.feedUrlFromText(
                "Subscribe to this show: https://example.com/show.xml.",
            ),
        )
    }

    @Test
    fun normalizesItpcPodcastLinksToHttpsFeedUrls() {
        assertEquals(
            "https://feeds.example.com/show",
            IncomingPodcastIntents.normalizeFeedUrlCandidate("itpc://feeds.example.com/show"),
        )
    }

    @Test
    fun normalizesFeedSchemeWrappingAnHttpsUrl() {
        assertEquals(
            "https://example.com/rss",
            IncomingPodcastIntents.normalizeFeedUrlCandidate("feed:https://example.com/rss"),
        )
    }

    @Test
    fun decodesEncodedPcastPayloads() {
        assertEquals(
            "https://example.com/rss.xml",
            IncomingPodcastIntents.normalizeFeedUrlCandidate(
                "pcast://https%3A%2F%2Fexample.com%2Frss.xml",
            ),
        )
    }

    @Test
    fun ignoresTextWithoutSubscriptionUrls() {
        assertNull(IncomingPodcastIntents.feedUrlFromText("listen to this one later"))
    }
}
