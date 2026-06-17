package com.smgray.easypod.feeds

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyndicationParserTest {
    private val parser = SyndicationParser()

    @Test
    fun parsesRssPodcastFields() {
        val feed = parser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>EasyPod Test</title>
                <link>https://example.com</link>
                <itunes:image href="https://example.com/cover.jpg"/>
                <item>
                  <title>Episode One</title>
                  <guid>episode-1</guid>
                  <pubDate>Thu, 21 Jan 2021 12:00:00 +0000</pubDate>
                  <itunes:duration>01:02:03</itunes:duration>
                  <enclosure url="https://example.com/one.mp3"
                    type="audio/mpeg" length="12345"/>
                </item>
              </channel>
            </rss>
            """.trimIndent().asStream(),
        )

        assertEquals("EasyPod Test", feed.title)
        assertEquals("https://example.com/cover.jpg", feed.imageUrl)
        assertEquals(1, feed.episodes.size)
        assertEquals("episode-1", feed.episodes.single().id)
        assertEquals(3_723_000L, feed.episodes.single().durationMs)
        assertNotNull(feed.episodes.single().publishedAt)
    }

    @Test
    fun parsesAtomEnclosure() {
        val feed = parser.parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Test</title>
              <entry>
                <id>tag:example.com,2026:1</id>
                <title>Atom Episode</title>
                <updated>2026-06-14T10:00:00Z</updated>
                <link rel="enclosure" type="audio/mp4"
                  href="https://example.com/episode.m4a" length="99"/>
              </entry>
            </feed>
            """.trimIndent().asStream(),
        )

        assertEquals("Atom Test", feed.title)
        assertEquals("https://example.com/episode.m4a", feed.episodes.single().mediaUrl)
        assertEquals(99L, feed.episodes.single().mediaLength)
    }

    @Test
    fun doesNotExpandDocumentEntities() {
        assertThrows(Exception::class.java) {
            parser.parse(
                """
                <!DOCTYPE rss [
                  <!ENTITY title SYSTEM "file:///data/local/tmp/easypod-secret">
                ]>
                <rss><channel><title>&title;</title></channel></rss>
                """.trimIndent().asStream(),
            )
        }
    }

    private fun String.asStream() = ByteArrayInputStream(toByteArray())
}
