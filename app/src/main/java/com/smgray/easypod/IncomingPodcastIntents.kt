package com.smgray.easypod

import android.content.Intent
import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed interface IncomingPodcastRequest {
    data class Subscribe(val feedUrl: String) : IncomingPodcastRequest
    data class ImportOpml(val uri: Uri) : IncomingPodcastRequest
    data class ImportLegacyBackup(val uri: Uri) : IncomingPodcastRequest
    data class RestoreBackup(val uri: Uri) : IncomingPodcastRequest
}

object IncomingPodcastIntents {
    fun from(intent: Intent): IncomingPodcastRequest? =
        when (intent.action) {
            Intent.ACTION_SEND -> fromSend(intent)
            Intent.ACTION_VIEW -> fromView(intent)
            else -> null
        }

    internal fun feedUrlFromText(text: String): String? =
        FeedUrlCandidatePattern.find(text)
            ?.value
            ?.let(::normalizeFeedUrlCandidate)

    internal fun normalizeFeedUrlCandidate(raw: String): String? {
        val candidate = raw.trimUrlPunctuation()
        val lower = candidate.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> candidate
            lower.startsWith("feed://") -> normalizeFeedPayload(candidate.substringAfter("feed://"))
            lower.startsWith("feed:") -> normalizeFeedPayload(candidate.substringAfter("feed:"))
            lower.startsWith("itpc://") -> normalizeFeedPayload(candidate.substringAfter("itpc://"))
            lower.startsWith("pcast://") -> normalizeFeedPayload(candidate.substringAfter("pcast://"))
            lower.startsWith("rss://") -> normalizeFeedPayload(candidate.substringAfter("rss://"))
            else -> null
        }
    }

    private fun fromSend(intent: Intent): IncomingPodcastRequest? {
        val type = intent.type
        val stream = intent.streamUri()
        if (stream != null) {
            fileRequest(stream, type)?.let { return it }
        }

        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.let(::feedUrlFromText)
            ?.let(IncomingPodcastRequest::Subscribe)
    }

    private fun fromView(intent: Intent): IncomingPodcastRequest? {
        val uri = intent.data ?: return null
        fileRequest(uri, intent.type)?.let { return it }
        return normalizeFeedUrlCandidate(uri.toString())
            ?.let(IncomingPodcastRequest::Subscribe)
    }

    private fun fileRequest(uri: Uri, type: String?): IncomingPodcastRequest? =
        when {
            isOpml(uri, type) -> IncomingPodcastRequest.ImportOpml(uri)
            isLegacyBackup(uri, type) -> IncomingPodcastRequest.ImportLegacyBackup(uri)
            isEasyPodBackup(uri, type) -> IncomingPodcastRequest.RestoreBackup(uri)
            else -> null
        }

    private fun isOpml(uri: Uri, type: String?): Boolean {
        val normalizedType = type?.lowercase()
        return normalizedType == "text/x-opml" ||
            uri.extension() == "opml" ||
            (uri.extension() == "xml" && normalizedType in XmlMimeTypes)
    }

    private fun isLegacyBackup(uri: Uri, type: String?): Boolean {
        val normalizedType = type?.lowercase()
        return normalizedType in LegacyBackupMimeTypes ||
            uri.extension() == "bpbak"
    }

    private fun isEasyPodBackup(uri: Uri, type: String?): Boolean {
        val normalizedType = type?.lowercase()
        return normalizedType == "application/zip" ||
            uri.extension() == "zip"
    }

    private fun normalizeFeedPayload(value: String): String? {
        val payload = value.trim().removePrefix("//").trimUrlPunctuation()
        if (payload.isBlank()) return null

        val decoded = runCatching {
            URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
        }.getOrDefault(payload).removePrefix("//")

        return when {
            decoded.startsWith("http://", ignoreCase = true) ||
                decoded.startsWith("https://", ignoreCase = true) -> decoded
            else -> "https://$decoded"
        }
    }

    private fun String.trimUrlPunctuation(): String =
        trim()
            .trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'')

    private fun Uri.extension(): String =
        lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            .orEmpty()

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    private val FeedUrlCandidatePattern =
        Regex("""(?i)\b(?:https?://|itpc://|pcast://|rss://|feed:(?://)?)[^\s<>"']+""")

    private val XmlMimeTypes = setOf(
        "application/xml",
        "text/xml",
        "application/rss+xml",
        "application/atom+xml",
    )

    private val LegacyBackupMimeTypes = setOf(
        "application/bpbak",
        "application/x-bpbak",
    )
}
