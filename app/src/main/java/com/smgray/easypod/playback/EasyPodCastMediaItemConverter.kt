package com.smgray.easypod.playback

import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem

@OptIn(UnstableApi::class)
internal class EasyPodCastMediaItemConverter(
    private val delegate: MediaItemConverter = DefaultMediaItemConverter(),
) : MediaItemConverter {
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val remoteUri = mediaItem.requestMetadata.mediaUri
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }
        val remoteItem = if (remoteUri == null) {
            mediaItem
        } else {
            mediaItem.buildUpon().setUri(remoteUri).build()
        }
        return delegate.toMediaQueueItem(remoteItem)
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        delegate.toMediaItem(mediaQueueItem)
}
