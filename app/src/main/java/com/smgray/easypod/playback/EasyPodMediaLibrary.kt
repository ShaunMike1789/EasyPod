package com.smgray.easypod.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.smgray.easypod.BuildConfig
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.smgray.easypod.data.CarEpisodeSummary
import com.smgray.easypod.data.FeedEntity
import com.smgray.easypod.data.EasyPodDatabase
import com.smgray.easypod.data.EasyPodRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object EasyPodMediaIds {
    const val ROOT = "easypod:root"
    const val PLAYLIST = "easypod:playlist"
    const val UNPLAYED = "easypod:unplayed"
    const val FEEDS = "easypod:feeds"
    const val DOWNLOADS = "easypod:downloads"
    private const val FEED_PREFIX = "easypod:feed:"

    fun feed(feedId: String): String = "$FEED_PREFIX$feedId"

    fun feedId(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(FEED_PREFIX) }
            ?.removePrefix(FEED_PREFIX)
            ?.takeIf(String::isNotBlank)
}

internal fun <T> pageMediaItems(
    items: List<T>,
    page: Int,
    pageSize: Int,
): List<T>? {
    if (page < 0 || pageSize <= 0) return null
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    val start = from.toInt()
    return items.subList(start, minOf(start + pageSize, items.size))
}

internal fun shouldAcceptMediaController(
    controllerPackage: String,
    isTrusted: Boolean,
    isSystemApplication: Boolean,
    isLegacyController: Boolean,
): Boolean =
    controllerPackage == BuildConfig.APPLICATION_ID ||
        isTrusted ||
        isSystemApplication ||
        isLegacyController

@Suppress("DEPRECATION")
internal fun isSystemApplication(context: Context, packageName: String): Boolean =
    try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (
            ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            ) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

@OptIn(UnstableApi::class)
internal class EasyPodMediaLibraryCallback(
    private val database: EasyPodDatabase,
    private val repository: EasyPodRepository,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    private val libraryDao = database.libraryDao()

    override fun onPlaybackResumption(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        ioFuture("playback-resumption") {
            val queue = libraryDao.getCarQueue(MAX_EPISODES)
            val mediaItems = queue.mapNotNull(::episodeItem)
            check(mediaItems.isNotEmpty()) { "There is no saved playlist to resume" }

            val state = repository.getPlaybackState()
            val startIndex = state?.currentEpisodeId
                ?.let { currentId ->
                    mediaItems.indexOfFirst { it.mediaId == currentId }
                }
                ?.takeIf { it >= 0 }
                ?: 0
            val storedEpisodePosition = queue
                .firstOrNull { it.id == mediaItems[startIndex].mediaId }
                ?.positionMs
                ?: 0L
            val startPositionMs = state?.positionMs
                ?.coerceAtLeast(storedEpisodePosition)
                ?.coerceAtLeast(0L)
                ?: storedEpisodePosition.coerceAtLeast(0L)
            state?.playbackSpeed
                ?.takeIf { it in 0.5f..3f }
                ?.let { speed ->
                    withContext(Dispatchers.Main.immediate) {
                        session.player.setPlaybackSpeed(speed)
                    }
                }

            MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                startIndex,
                startPositionMs,
            )
        }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        immediateFuture {
            LibraryResult.ofItem(rootItem(), params)
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        ioFuture("children:$parentId") {
            val items = when (parentId) {
                EasyPodMediaIds.ROOT -> rootChildren()
                EasyPodMediaIds.PLAYLIST ->
                    libraryDao.getCarQueue(MAX_EPISODES).mapNotNull(::episodeItem)

                EasyPodMediaIds.UNPLAYED ->
                    libraryDao.getCarUnplayedEpisodes(MAX_EPISODES)
                        .mapNotNull(::episodeItem)

                EasyPodMediaIds.FEEDS ->
                    libraryDao.getAllFeeds().map(::feedItem)

                EasyPodMediaIds.DOWNLOADS ->
                    libraryDao.getCarDownloadedEpisodes(MAX_EPISODES)
                        .mapNotNull(::episodeItem)

                else -> EasyPodMediaIds.feedId(parentId)
                    ?.let { feedId ->
                        libraryDao.getCarEpisodesForFeed(feedId, MAX_EPISODES)
                            .mapNotNull(::episodeItem)
                    }
                    ?: return@ioFuture LibraryResult.ofError(
                        SessionError.ERROR_BAD_VALUE,
                        params,
                    )
            }
            val pageItems = pageMediaItems(items, page, pageSize)
                ?: return@ioFuture LibraryResult.ofError(
                    SessionError.ERROR_BAD_VALUE,
                    params,
                )
            LibraryResult.ofItemList(pageItems, params)
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        ioFuture("item:$mediaId") {
            val item = when (mediaId) {
                EasyPodMediaIds.ROOT -> rootItem()
                EasyPodMediaIds.PLAYLIST -> folderItem(
                    mediaId,
                    "Playlist",
                    "Your current EasyPod queue",
                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                )

                EasyPodMediaIds.UNPLAYED -> folderItem(
                    mediaId,
                    "Unplayed",
                    "Recent episodes you have not finished",
                    MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
                )

                EasyPodMediaIds.FEEDS -> folderItem(
                    mediaId,
                    "Subscriptions",
                    "Browse podcasts by feed",
                    MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
                )

                EasyPodMediaIds.DOWNLOADS -> folderItem(
                    mediaId,
                    "Downloads",
                    "Episodes available offline",
                    MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
                )

                else -> {
                    val feedId = EasyPodMediaIds.feedId(mediaId)
                    if (feedId != null) {
                        libraryDao.findFeed(feedId)?.let(::feedItem)
                    } else {
                        libraryDao.getCarEpisode(mediaId)?.let(::episodeItem)
                    }
                }
            }
            item?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        ioFuture("search:$query") {
            val normalized = query.trim()
            if (normalized.isEmpty()) {
                return@ioFuture LibraryResult.ofError(
                    SessionError.ERROR_BAD_VALUE,
                    params,
                )
            }
            val count = libraryDao.searchCarEpisodes(
                escapeLikeQuery(normalized),
                MAX_EPISODES,
            ).size
            session.notifySearchResultChanged(browser, normalized, count, params)
            LibraryResult.ofVoid(params)
        }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        ioFuture("search-results:$query") {
            val normalized = query.trim()
            if (normalized.isEmpty()) {
                return@ioFuture LibraryResult.ofError(
                    SessionError.ERROR_BAD_VALUE,
                    params,
                )
            }
            val results = libraryDao.searchCarEpisodes(
                escapeLikeQuery(normalized),
                MAX_EPISODES,
            ).mapNotNull(::episodeItem)
            val pageItems = pageMediaItems(results, page, pageSize)
                ?: return@ioFuture LibraryResult.ofError(
                    SessionError.ERROR_BAD_VALUE,
                    params,
                )
            LibraryResult.ofItemList(pageItems, params)
        }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> =
        ioFuture("resolve-playback-items") {
            mediaItems.mapNotNull { requested ->
                val episode = libraryDao.getCarEpisode(requested.mediaId)
                if (episode != null) {
                    repository.enqueueEpisode(episode.id)
                    episodeItem(episode)
                } else {
                    null
                }
            }
        }

    private fun rootItem(): MediaItem = folderItem(
        mediaId = EasyPodMediaIds.ROOT,
        title = "EasyPod",
        subtitle = "Podcasts, on your terms",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
    )

    private fun rootChildren(): List<MediaItem> = listOf(
        folderItem(
            EasyPodMediaIds.PLAYLIST,
            "Playlist",
            "Your current EasyPod queue",
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
        ),
        folderItem(
            EasyPodMediaIds.UNPLAYED,
            "Unplayed",
            "Recent episodes you have not finished",
            MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
        ),
        folderItem(
            EasyPodMediaIds.FEEDS,
            "Subscriptions",
            "Browse podcasts by feed",
            MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
        ),
        folderItem(
            EasyPodMediaIds.DOWNLOADS,
            "Downloads",
            "Episodes available offline",
            MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
        ),
    )

    private fun feedItem(feed: FeedEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(EasyPodMediaIds.feed(feed.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(feed.title)
                    .setSubtitle("Podcast subscription")
                    .setArtworkUri(feed.imageUrl.toSafeUri())
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .build(),
            )
            .build()

    private fun episodeItem(episode: CarEpisodeSummary): MediaItem? {
        val remoteUri = episode.mediaUrl.toSafeUri()
        val uri = episode.localDownloadPath
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.let(Uri::fromFile)
            ?: remoteUri
            ?: return null
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .setMimeType(episode.mimeType)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(remoteUri)
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.feedTitle)
                    .setAlbumTitle(episode.feedTitle)
                    .setArtworkUri(episode.imageUrl.toSafeUri())
                    .setDurationMs(episode.durationMs.takeIf { it > 0 })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            )
            .build()
    }

    private fun folderItem(
        mediaId: String,
        title: String,
        subtitle: String,
        mediaType: Int,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build(),
            )
            .build()

    private fun String?.toSafeUri(): Uri? =
        this?.trim()?.takeIf(String::isNotEmpty)?.let(Uri::parse)

    private fun escapeLikeQuery(query: String): String =
        query.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun <T> immediateFuture(block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            runCatching(block)
                .onSuccess(completer::set)
                .onFailure(completer::setException)
            "EasyPod immediate media result"
        }

    private fun <T> ioFuture(
        label: String,
        block: suspend () -> T,
    ): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch(Dispatchers.IO) {
                runCatching { block() }
                    .onSuccess(completer::set)
                    .onFailure(completer::setException)
            }
            "EasyPod media library $label"
        }

    private companion object {
        const val MAX_EPISODES = 500
    }
}
