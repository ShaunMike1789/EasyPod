package com.smgray.easypod.feeds

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastDirectorySearchParserTest {
    @Test
    fun parsesDirectoryResultsAndDeduplicatesFeeds() {
        val results = PodcastDirectorySearchParser.parse(
            """
            {
              "resultCount": 3,
              "results": [
                {
                  "collectionName": "Science Weekly",
                  "artistName": "Example Radio",
                  "feedUrl": "http://example.com/science.xml",
                  "artworkUrl100": "https://example.com/art.jpg",
                  "genres": ["Science", "Education"]
                },
                {
                  "collectionName": "Duplicate",
                  "feedUrl": "http://example.com/science.xml"
                },
                {
                  "collectionName": "No Feed"
                }
              ]
            }
            """.trimIndent().asStream(),
        )

        assertEquals(1, results.size)
        assertEquals("Science Weekly", results.single().title)
        assertEquals("Example Radio", results.single().author)
        assertEquals("http://example.com/science.xml", results.single().feedUrl)
        assertEquals(listOf("Science", "Education"), results.single().genres)
    }

    @Test
    fun fallsBackToFeedUrlWhenTitleIsMissing() {
        val results = PodcastDirectorySearchParser.parse(
            """
            {
              "results": [
                { "feedUrl": "https://example.com/untitled.rss" }
              ]
            }
            """.trimIndent().asStream(),
        )

        assertEquals("https://example.com/untitled.rss", results.single().title)
    }

    private fun String.asStream() = ByteArrayInputStream(toByteArray())
}
