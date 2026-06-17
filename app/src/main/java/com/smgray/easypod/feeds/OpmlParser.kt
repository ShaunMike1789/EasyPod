package com.smgray.easypod.feeds

import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class OpmlSubscription(
    val title: String,
    val feedUrl: String,
    val siteUrl: String?,
    val category: String?,
)

class OpmlParser {
    fun parse(input: InputStream): List<OpmlSubscription> {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser().apply {
            runCatching {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }
            setInput(input, null)
        }

        val subscriptions = mutableListOf<OpmlSubscription>()
        var depth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    require(depth <= MAX_XML_DEPTH) {
                        "The OPML document is nested too deeply"
                    }
                    if (parser.name.equals("outline", ignoreCase = true)) {
                        val feedUrl = parser.attribute("xmlUrl")
                        if (!feedUrl.isNullOrBlank()) {
                            require(subscriptions.size < MAX_SUBSCRIPTIONS) {
                                "The OPML document contains too many subscriptions"
                            }
                            subscriptions += OpmlSubscription(
                                title = parser.attribute("title")
                                    ?: parser.attribute("text")
                                    ?: feedUrl,
                                feedUrl = feedUrl,
                                siteUrl = parser.attribute("htmlUrl"),
                                category = parser.attribute("category"),
                            )
                        }
                    }
                }

                XmlPullParser.END_TAG -> depth--
            }
            event = parser.next()
        }

        require(subscriptions.isNotEmpty()) {
            "The selected document contains no OPML podcast subscriptions"
        }
        return subscriptions.distinctBy { it.feedUrl.lowercase() }
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).equals(name, ignoreCase = true)) {
                return getAttributeValue(index)?.trim()?.takeIf(String::isNotEmpty)
            }
        }
        return null
    }

    private companion object {
        const val MAX_XML_DEPTH = 64
        const val MAX_SUBSCRIPTIONS = 10_000
    }
}
