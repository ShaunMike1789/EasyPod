package com.smgray.easypod.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.smgray.easypod.data.QueueEpisodeSummary
import java.io.File

internal fun QueueEpisodeSummary.toMediaItem(): MediaItem? {
    val remoteUri = mediaUrl?.takeIf(String::isNotBlank)?.let(Uri::parse)
    val uri = localDownloadPath
        ?.let(::File)
        ?.takeIf(File::exists)
        ?.let(Uri::fromFile)
        ?: remoteUri
        ?: return null

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMimeType(mimeType)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(remoteUri)
                .build(),
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(feedTitle)
                .setAlbumTitle(feedTitle)
                .setArtworkUri(imageUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build(),
        )
        .build()
}
