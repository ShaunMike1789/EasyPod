package com.smgray.easypod.feeds

import android.content.Context
import android.util.Xml
import android.net.Uri
import androidx.room.withTransaction
import com.smgray.easypod.BuildConfig
import com.smgray.easypod.data.EpisodeEntity
import com.smgray.easypod.data.FeedEntity
import com.smgray.easypod.data.EasyPodDatabase
import com.smgray.easypod.widgets.EasyPodWidgets
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class FeedRefreshResult(
    val feedTitle: String,
    val episodeCount: Int,
    val newEpisodeIds: List<String> = emptyList(),
)

data class OpmlImportResult(
    val imported: Int,
    val failed: Int,
)

data class OpmlExportResult(
    val exported: Int,
)

class FeedIngestionService(
    private val context: Context,
    private val database: EasyPodDatabase,
    private val client: OkHttpClient = OkHttpClient(),
    private val syndicationParser: SyndicationParser = SyndicationParser(),
    private val opmlParser: OpmlParser = OpmlParser(),
) {
    suspend fun addOrRefresh(
        rawUrl: String,
        refreshWidgets: Boolean = true,
    ): FeedRefreshResult =
        withContext(Dispatchers.IO) {
            val url = normalizePodcastFeedUrl(rawUrl)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "EasyPod/${BuildConfig.VERSION_NAME}")
                .header(
                    "Accept",
                    "application/rss+xml, application/atom+xml, application/xml, text/xml",
                )
                .build()

            val parsed = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Feed request failed with HTTP ${response.code}")
                }
                val body = response.body
                syndicationParser.parse(
                    LimitedInputStream(body.byteStream(), MAX_FEED_BYTES),
                )
            }

            val dao = database.libraryDao()
            val existing = dao.findFeedByUrl(url)
            val feedId = existing?.id ?: stableId("feed", url)
            val existingEpisodes = dao.getEpisodesForFeed(feedId)
                .associateBy(EpisodeEntity::id)
            val feed = existing?.copy(
                title = parsed.title,
                imageUrl = parsed.imageUrl ?: existing.imageUrl,
                publishedAt = parsed.episodes.maxOfOrNull { it.publishedAt ?: 0L },
                hasUnread = parsed.episodes.isNotEmpty(),
            ) ?: newFeed(feedId, parsed.title, url, parsed.imageUrl)

            val newEpisodeIds = mutableListOf<String>()
            val episodes = parsed.episodes.map { item ->
                val identity = item.id ?: item.mediaUrl ?: item.postUrl
                    ?: "${item.title}|${item.publishedAt}"
                val episodeId = stableId("episode:$feedId", identity)
                val stored = existingEpisodes[episodeId]
                if (stored == null) newEpisodeIds += episodeId
                stored?.copy(
                    legacyRssItemId = item.id ?: stored.legacyRssItemId,
                    title = item.title,
                    mediaUrl = item.mediaUrl ?: stored.mediaUrl,
                    postUrl = item.postUrl ?: stored.postUrl,
                    imagePath = item.imageUrl ?: stored.imagePath,
                    mimeType = item.mediaType ?: stored.mimeType,
                    protocol = item.mediaUrl
                        ?.substringBefore(":", missingDelimiterValue = "")
                        ?: stored.protocol,
                    durationMs = item.durationMs.takeIf { it > 0 } ?: stored.durationMs,
                    publishedAt = item.publishedAt ?: stored.publishedAt,
                    downloadSizeBytes = item.mediaLength
                        .takeIf { it > 0 }
                        ?: stored.downloadSizeBytes,
                    description = item.description ?: stored.description,
                    showNotes = item.description ?: stored.showNotes,
                ) ?: EpisodeEntity(
                    id = episodeId,
                    legacyRowId = null,
                    legacyItemId = null,
                    legacyRssItemId = item.id,
                    legacyParentFeedId = null,
                    feedId = feedId,
                    title = item.title,
                    mediaUrl = item.mediaUrl,
                    postUrl = item.postUrl,
                    localPath = null,
                    imagePath = item.imageUrl,
                    mimeType = item.mediaType,
                    protocol = item.mediaUrl?.substringBefore(":", missingDelimiterValue = ""),
                    durationMs = item.durationMs,
                    positionMs = 0,
                    publishedAt = item.publishedAt,
                    downloadSizeBytes = item.mediaLength,
                    downloadPortionRaw = 0,
                    played = false,
                    locked = false,
                    description = item.description,
                    showNotes = item.description,
                )
            }

            database.withTransaction {
                dao.putFeeds(listOf(feed))
                dao.putEpisodes(episodes)
            }
            if (refreshWidgets) {
                EasyPodWidgets.updateAll(context)
            }
            FeedRefreshResult(
                feedTitle = parsed.title,
                episodeCount = episodes.size,
                newEpisodeIds = newEpisodeIds,
            )
        }

    suspend fun importOpml(uri: Uri): OpmlImportResult = withContext(Dispatchers.IO) {
        val subscriptions = context.contentResolver.openInputStream(uri)?.use { input ->
            opmlParser.parse(LimitedInputStream(input, MAX_OPML_BYTES))
        } ?: throw IOException("Unable to open the selected OPML file")

        var failed = 0
        subscriptions.forEach { subscription ->
            try {
                putPlaceholder(subscription)
                addOrRefresh(subscription.feedUrl, refreshWidgets = false)
            } catch (_: Exception) {
                failed++
            }
        }
        EasyPodWidgets.updateAll(context)
        OpmlImportResult(imported = subscriptions.size, failed = failed)
    }

    suspend fun exportOpml(uri: Uri): OpmlExportResult = withContext(Dispatchers.IO) {
        val feeds = database.libraryDao().getOpmlExportFeeds()
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, StandardCharsets.UTF_8.name())
            serializer.startDocument(StandardCharsets.UTF_8.name(), true)
            serializer.startTag(null, "opml")
            serializer.attribute(null, "version", "2.0")
            serializer.startTag(null, "head")
            serializer.startTag(null, "title")
            serializer.text("EasyPod subscriptions")
            serializer.endTag(null, "title")
            serializer.endTag(null, "head")
            serializer.startTag(null, "body")
            feeds.forEach { feed ->
                serializer.startTag(null, "outline")
                serializer.attribute(null, "text", feed.title)
                serializer.attribute(null, "title", feed.title)
                serializer.attribute(null, "type", "rss")
                serializer.attribute(null, "xmlUrl", feed.feedUrl)
                feed.categoryNames?.takeIf(String::isNotBlank)?.let {
                    serializer.attribute(null, "category", it)
                }
                serializer.endTag(null, "outline")
            }
            serializer.endTag(null, "body")
            serializer.endTag(null, "opml")
            serializer.endDocument()
            output.flush()
        } ?: throw IOException("Unable to create the OPML document")
        OpmlExportResult(feeds.size)
    }

    private suspend fun putPlaceholder(subscription: OpmlSubscription) {
        val url = normalizePodcastFeedUrl(subscription.feedUrl)
        val dao = database.libraryDao()
        if (dao.findFeedByUrl(url) != null) return
        dao.putFeeds(
            listOf(
                newFeed(
                    id = stableId("feed", url),
                    title = subscription.title,
                    url = url,
                    imageUrl = null,
                    categoryRaw = subscription.category,
                ),
            ),
        )
    }

    private fun newFeed(
        id: String,
        title: String,
        url: String,
        imageUrl: String?,
        categoryRaw: String? = null,
    ) = FeedEntity(
        id = id,
        legacyRowId = null,
        legacyFeedId = null,
        title = title,
        feedUrl = url,
        imageUrl = imageUrl,
        localPath = null,
        categoryAssignmentRaw = categoryRaw,
        autoDelete = false,
        viewMode = 0,
        feedType = 0,
        hasUnread = false,
        publishedAt = null,
        serverPublishedAt = null,
        customDownload = 0,
        forceUniqueNames = false,
        truncateLongNames = false,
        forceItemSort = false,
        lastItemId = null,
        trackSort = 0,
        feedPlayer = 0,
        username = null,
        maxDownloads = 0,
        maxSyncedItems = 0,
        maxEpisodes = 0,
        maxEpisodeAge = 0,
        savePlayedPosition = true,
        fingerprintType = 0,
        audioSettings = null,
    )

    private fun stableId(namespace: String, value: String): String =
        UUID.nameUUIDFromBytes(
            "$namespace\u0000$value".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    private class LimitedInputStream(
        input: InputStream,
        private val limit: Long,
    ) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) track(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) track(read)
            return read
        }

        private fun track(count: Int) {
            total += count
            require(total <= limit) { "The selected document is too large" }
        }
    }

    private companion object {
        const val MAX_FEED_BYTES = 10L * 1024L * 1024L
        const val MAX_OPML_BYTES = 5L * 1024L * 1024L
    }
}

internal fun normalizePodcastFeedUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { "Enter a podcast feed URL" }
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = requireNotNull(candidate.toHttpUrlOrNull()) {
        "Enter a valid podcast feed URL"
    }
    require(url.isHttps) { "Podcast feeds must use HTTPS" }
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "Podcast feed URLs cannot contain credentials"
    }
    return url.toString()
}
