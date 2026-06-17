package com.smgray.easypod.feeds

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class PodcastDirectoryResult(
    val title: String,
    val author: String?,
    val feedUrl: String,
    val artworkUrl: String?,
    val genres: List<String>,
)

internal object PodcastDirectorySearchParser {
    fun parse(input: InputStream): List<PodcastDirectoryResult> {
        val results = mutableListOf<PodcastDirectoryResult>()
        JsonReader(input.reader(StandardCharsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "results" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readResult(reader)?.let(results::add)
                        }
                        reader.endArray()
                    }

                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return results.distinctBy { it.feedUrl }
    }

    private fun readResult(reader: JsonReader): PodcastDirectoryResult? {
        var title: String? = null
        var author: String? = null
        var feedUrl: String? = null
        var artworkUrl: String? = null
        var genres = emptyList<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "collectionName", "trackName" -> {
                    if (title == null) title = reader.nextStringOrNull()
                    else reader.skipValue()
                }

                "artistName" -> author = reader.nextStringOrNull()
                "feedUrl" -> feedUrl = reader.nextStringOrNull()
                "artworkUrl100", "artworkUrl600" -> {
                    if (artworkUrl == null) artworkUrl = reader.nextStringOrNull()
                    else reader.skipValue()
                }

                "genres" -> genres = reader.nextStringArray()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val normalizedFeedUrl = feedUrl
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { normalizePodcastFeedUrl(it) }.getOrNull() }
            ?: return null
        return PodcastDirectoryResult(
            title = title?.takeIf(String::isNotBlank) ?: normalizedFeedUrl,
            author = author?.takeIf(String::isNotBlank),
            feedUrl = normalizedFeedUrl,
            artworkUrl = artworkUrl?.takeIf(String::isNotBlank),
            genres = genres,
        )
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.nextStringArray(): List<String> {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return emptyList()
        }
        val values = mutableListOf<String>()
        beginArray()
        while (hasNext()) {
            nextStringOrNull()?.takeIf(String::isNotBlank)?.let(values::add)
        }
        endArray()
        return values
    }
}
