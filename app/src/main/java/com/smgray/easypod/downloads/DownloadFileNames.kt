package com.smgray.easypod.downloads

import java.net.URI

object DownloadFileNames {
    fun forEpisode(episodeId: String, mediaUrl: String, mimeType: String?): String {
        val extension = extensionFromUrl(mediaUrl)
            ?: extensionFromMimeType(mimeType)
            ?: ".media"
        return episodeId.replace(Regex("[^A-Za-z0-9._-]"), "_") + extension
    }

    private fun extensionFromUrl(mediaUrl: String): String? {
        val path = runCatching { URI(mediaUrl).path }.getOrNull().orEmpty()
        val extension = path.substringAfterLast(".", missingDelimiterValue = "")
            .lowercase()
        return extension
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?.let { ".$it" }
    }

    private fun extensionFromMimeType(mimeType: String?): String? = when (
        mimeType?.substringBefore(";")?.trim()?.lowercase()
    ) {
        "audio/mpeg" -> ".mp3"
        "audio/mp4", "audio/x-m4a" -> ".m4a"
        "audio/ogg", "application/ogg" -> ".ogg"
        "audio/opus" -> ".opus"
        "audio/flac" -> ".flac"
        "audio/wav", "audio/x-wav" -> ".wav"
        "video/mp4" -> ".mp4"
        else -> null
    }
}

