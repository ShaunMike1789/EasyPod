package com.smgray.easypod.media

enum class EpisodeMediaType {
    Audio,
    Video,
    Image,
}

object EpisodeMediaClassifier {
    fun classify(
        mimeType: String?,
        mediaUrl: String?,
    ): EpisodeMediaType {
        val mime = mimeType
            .orEmpty()
            .substringBefore(";")
            .trim()
            .lowercase()
        val url = mediaUrl
            .orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()

        return when {
            mime.startsWith("video/") ||
                url.endsWith(".mp4") ||
                url.endsWith(".m4v") ||
                url.endsWith(".webm") ||
                url.endsWith(".mov") -> EpisodeMediaType.Video

            mime.startsWith("image/") ||
                url.endsWith(".jpg") ||
                url.endsWith(".jpeg") ||
                url.endsWith(".png") -> EpisodeMediaType.Image

            else -> EpisodeMediaType.Audio
        }
    }

    fun labelFor(
        mimeType: String?,
        mediaUrl: String?,
    ): String = when (classify(mimeType, mediaUrl)) {
        EpisodeMediaType.Audio -> "Audio"
        EpisodeMediaType.Video -> "Video"
        EpisodeMediaType.Image -> "Image"
    }
}
