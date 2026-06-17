package com.smgray.easypod.feeds

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class ParsedFeed(
    val title: String,
    val description: String?,
    val siteUrl: String?,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val id: String?,
    val title: String,
    val mediaUrl: String?,
    val mediaType: String?,
    val mediaLength: Long,
    val postUrl: String?,
    val imageUrl: String?,
    val description: String?,
    val publishedAt: Long?,
    val durationMs: Long,
)

class SyndicationParser {
    fun parse(input: InputStream): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser().apply {
            runCatching {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }
            setInput(input, null)
        }

        var feedTitle: String? = null
        var feedDescription: String? = null
        var siteUrl: String? = null
        var feedImage: String? = null
        var episode: EpisodeBuilder? = null
        val episodes = mutableListOf<ParsedEpisode>()
        val stack = ArrayDeque<Frame>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    require(stack.size < MAX_XML_DEPTH) {
                        "The podcast feed is nested too deeply"
                    }
                    val name = parser.name.lowercase()
                    val parent = stack.lastOrNull()?.name
                    stack.addLast(
                        Frame(
                            name = name,
                            parent = parent,
                            namespace = parser.namespace.orEmpty(),
                            prefix = parser.prefix.orEmpty(),
                        ),
                    )

                    if (name == "item" || name == "entry") {
                        episode = EpisodeBuilder()
                    } else {
                        val isItunes = parser.namespace.orEmpty().contains("itunes") ||
                            parser.prefix.orEmpty().equals("itunes", ignoreCase = true)
                        val url = parser.attribute("url")
                        val href = parser.attribute("href")
                        val rel = parser.attribute("rel")?.lowercase()
                        val type = parser.attribute("type")

                        when {
                            episode != null && name == "enclosure" -> {
                                episode.mediaUrl = url
                                episode.mediaType = type
                                episode.mediaLength = parser.attribute("length")
                                    ?.toLongOrNull() ?: 0L
                            }

                            episode != null && name == "link" && href != null -> {
                                if (rel == "enclosure") {
                                    episode.mediaUrl = href
                                    episode.mediaType = type
                                    episode.mediaLength = parser.attribute("length")
                                        ?.toLongOrNull() ?: 0L
                                } else if (rel == null || rel == "alternate") {
                                    episode.postUrl = href
                                }
                            }

                            episode != null &&
                                (name == "thumbnail" || (name == "image" && isItunes)) -> {
                                episode.imageUrl = url ?: href
                            }

                            episode != null && name == "content" && url != null -> {
                                val medium = parser.attribute("medium")
                                if (
                                    medium.equals("audio", ignoreCase = true) ||
                                    type.orEmpty().startsWith("audio/")
                                ) {
                                    episode.mediaUrl = url
                                    episode.mediaType = type
                                    episode.mediaLength = parser.attribute("fileSize")
                                        ?.toLongOrNull() ?: 0L
                                }
                            }

                            episode == null && name == "link" && href != null -> {
                                if (rel == null || rel == "alternate") siteUrl = href
                            }

                            episode == null && name == "image" && isItunes -> {
                                feedImage = href ?: url
                            }
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    stack.lastOrNull()?.text?.append(parser.text)
                }

                XmlPullParser.END_TAG -> {
                    val frame = stack.removeLastOrNull()
                    if (frame != null) {
                        val value = frame.text.toString().trim()
                        val activeEpisode = episode

                        if (activeEpisode != null && frame.name !in setOf("item", "entry")) {
                            when (frame.name) {
                                "title" -> activeEpisode.title = value
                                "guid", "id" -> activeEpisode.id = value
                                "link" -> if (activeEpisode.postUrl == null && value.isNotEmpty()) {
                                    activeEpisode.postUrl = value
                                }
                                "description", "summary", "encoded", "content" ->
                                    if (value.isNotEmpty()) activeEpisode.description = value
                                "pubdate", "published", "updated", "date" ->
                                    activeEpisode.publishedAt = FeedDateParser.parse(value)
                                "duration" -> activeEpisode.durationMs = parseDuration(value)
                            }
                        } else if (activeEpisode == null) {
                            when {
                                frame.name == "title" &&
                                    frame.parent in setOf("channel", "feed") -> feedTitle = value
                                frame.name in setOf("description", "subtitle") &&
                                    frame.parent in setOf("channel", "feed") ->
                                    feedDescription = value
                                frame.name == "link" &&
                                    frame.parent == "channel" &&
                                    value.isNotEmpty() -> siteUrl = value
                                frame.name == "url" && frame.parent == "image" ->
                                    feedImage = value
                                frame.name in setOf("logo", "icon") &&
                                    frame.parent == "feed" -> feedImage = value
                            }
                        }

                        if (frame.name == "item" || frame.name == "entry") {
                            activeEpisode?.build()?.let {
                                require(episodes.size < MAX_EPISODES) {
                                    "The podcast feed contains too many episodes"
                                }
                                episodes += it
                            }
                            episode = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        require(!feedTitle.isNullOrBlank() || episodes.isNotEmpty()) {
            "The document is not a recognizable RSS or Atom feed"
        }
        return ParsedFeed(
            title = feedTitle?.takeIf(String::isNotBlank) ?: "Untitled feed",
            description = feedDescription?.takeIf(String::isNotBlank),
            siteUrl = siteUrl?.takeIf(String::isNotBlank),
            imageUrl = feedImage?.takeIf(String::isNotBlank),
            episodes = episodes,
        )
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).equals(name, ignoreCase = true)) {
                return getAttributeValue(index)?.trim()?.takeIf(String::isNotEmpty)
            }
        }
        return null
    }

    private fun parseDuration(value: String): Long {
        if (value.isBlank()) return 0L
        val parts = value.split(":").mapNotNull(String::toLongOrNull)
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> return 0L
        }
        return seconds * 1_000L
    }

    private data class Frame(
        val name: String,
        val parent: String?,
        val namespace: String,
        val prefix: String,
        val text: StringBuilder = StringBuilder(),
    )

    private data class EpisodeBuilder(
        var id: String? = null,
        var title: String? = null,
        var mediaUrl: String? = null,
        var mediaType: String? = null,
        var mediaLength: Long = 0,
        var postUrl: String? = null,
        var imageUrl: String? = null,
        var description: String? = null,
        var publishedAt: Long? = null,
        var durationMs: Long = 0,
    ) {
        fun build(): ParsedEpisode? {
            val finalTitle = title?.takeIf(String::isNotBlank)
                ?: description?.take(80)?.takeIf(String::isNotBlank)
                ?: return null
            return ParsedEpisode(
                id = id?.takeIf(String::isNotBlank),
                title = finalTitle,
                mediaUrl = mediaUrl,
                mediaType = mediaType,
                mediaLength = mediaLength,
                postUrl = postUrl,
                imageUrl = imageUrl,
                description = description,
                publishedAt = publishedAt,
                durationMs = durationMs,
            )
        }
    }

    private companion object {
        const val MAX_XML_DEPTH = 64
        const val MAX_EPISODES = 10_000
    }
}

private object FeedDateParser {
    private val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd",
    )

    fun parse(value: String): Long? {
        if (value.isBlank()) return null
        for (pattern in patterns) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("UTC")
            }
            runCatching { formatter.parse(value)?.time }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }
}
