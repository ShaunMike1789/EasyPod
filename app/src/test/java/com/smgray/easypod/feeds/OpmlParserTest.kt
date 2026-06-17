package com.smgray.easypod.feeds

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpmlParserTest {
    @Test
    fun parsesAndDeduplicatesSubscriptions() {
        val subscriptions = OpmlParser().parse(
            ByteArrayInputStream(
                """
                <opml version="2.0">
                  <body>
                    <outline text="News" xmlUrl="https://example.com/news.xml"
                      htmlUrl="https://example.com" category="Daily"/>
                    <outline title="Duplicate" xmlUrl="https://example.com/news.xml"/>
                  </body>
                </opml>
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(1, subscriptions.size)
        assertEquals("News", subscriptions.single().title)
        assertEquals("Daily", subscriptions.single().category)
    }

    @Test
    fun rejectsExcessiveNesting() {
        val nested = buildString {
            append("<opml><body>")
            repeat(65) { append("<outline>") }
            append("<outline xmlUrl=\"https://example.com/feed.xml\"/>")
            repeat(65) { append("</outline>") }
            append("</body></opml>")
        }

        assertThrows(Exception::class.java) {
            OpmlParser().parse(
                ByteArrayInputStream(nested.toByteArray()),
            )
        }
    }
}
