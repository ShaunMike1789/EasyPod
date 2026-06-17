package com.smgray.easypod.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EasyPodMediaLibraryInstrumentedTest {
    @OptIn(UnstableApi::class)
    @Test
    fun castConverterUsesRemoteEnclosureForDownloadedEpisode() {
        val localUri = Uri.parse("file:///data/user/0/com.smgray.easypod/episode.mp3")
        val remoteUri = Uri.parse("https://cdn.example.com/episode.mp3")
        val mediaItem = MediaItem.Builder()
            .setMediaId("episode-1")
            .setUri(localUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(remoteUri)
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Downloaded episode")
                    .build(),
            )
            .build()

        val queueItem = EasyPodCastMediaItemConverter()
            .toMediaQueueItem(mediaItem)

        assertEquals(
            remoteUri.toString(),
            requireNotNull(queueItem.media).contentUrl,
        )
    }

    @Test
    fun browserConnectsAndReadsDriverSafeRoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val browserFuture = MediaBrowser.Builder(
            context,
            SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java),
            ),
        ).buildAsync()
        val browser = browserFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        try {
            val rootResult = browser.onApplicationThread {
                getLibraryRoot(null)
            }
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, rootResult.resultCode)
            val root = requireNotNull(rootResult.value)
            assertEquals(EasyPodMediaIds.ROOT, root.mediaId)
            assertTrue(root.mediaMetadata.isBrowsable == true)
            assertFalse(root.mediaMetadata.isPlayable == true)

            val childrenResult = browser.onApplicationThread {
                getChildren(
                    root.mediaId,
                    0,
                    20,
                    null,
                )
            }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, childrenResult.resultCode)
            assertEquals(
                listOf(
                    EasyPodMediaIds.PLAYLIST,
                    EasyPodMediaIds.UNPLAYED,
                    EasyPodMediaIds.FEEDS,
                    EasyPodMediaIds.DOWNLOADS,
                ),
                requireNotNull(childrenResult.value).map { it.mediaId },
            )
        } finally {
            browser.onApplicationThread {
                release()
            }
        }
    }

    private fun <T> MediaBrowser.onApplicationThread(
        block: MediaBrowser.() -> T,
    ): T {
        val result = CompletableFuture<T>()
        Handler(applicationLooper).post {
            runCatching { block() }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
