package com.smgray.easypod.smartplay

import com.smgray.easypod.data.SmartPlayCandidate
import com.smgray.easypod.downloads.DownloadState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPlayRuleEngineTest {
    @Test
    fun latestRuleLimitsAndDeduplicates() {
        val result = SmartPlayRuleEngine.select(
            rule = SmartPlayRuleSpec(
                episodeCount = 2,
                mediaType = SmartPlayRuleEngine.MEDIA_ANY,
                sortMode = SmartPlayRuleEngine.SORT_LATEST,
            ),
            candidates = listOf(
                candidate("old", 1),
                candidate("middle", 2),
                candidate("new", 3),
            ),
            excludedEpisodeIds = setOf("middle"),
        )

        assertEquals(listOf("new", "old"), result.map { it.id })
    }

    @Test
    fun automaticDownloadFeedWaitsUntilDownloadStarts() {
        val remote = candidate("remote", 2, feedDownloadAction = 1)
        val queued = candidate(
            "queued",
            1,
            feedDownloadAction = 1,
            downloadState = DownloadState.QUEUED,
        )

        val result = SmartPlayRuleEngine.select(
            rule = SmartPlayRuleSpec(0, SmartPlayRuleEngine.MEDIA_ANY, 3),
            candidates = listOf(remote, queued),
        )

        assertEquals(listOf("queued"), result.map { it.id })
    }

    @Test
    fun mediaFilterAndSeededRandomAreStable() {
        val candidates = listOf(
            candidate("audio", 1, mimeType = "audio/mpeg"),
            candidate("video", 2, mimeType = "video/mp4"),
        )

        val result = SmartPlayRuleEngine.select(
            rule = SmartPlayRuleSpec(0, SmartPlayRuleEngine.MEDIA_VIDEO, 100),
            candidates = candidates,
            random = Random(4),
        )

        assertEquals(listOf("video"), result.map { it.id })
    }

    private fun candidate(
        id: String,
        publishedAt: Long,
        feedDownloadAction: Int = 0,
        downloadState: String? = null,
        mimeType: String? = "audio/mpeg",
    ) = SmartPlayCandidate(
        id = id,
        title = id,
        feedTitle = "Feed",
        mediaUrl = "https://example.com/$id.mp3",
        mimeType = mimeType,
        localDownloadPath = null,
        downloadState = downloadState,
        feedDownloadAction = feedDownloadAction,
        publishedAt = publishedAt,
        positionMs = 0,
        durationMs = 1,
    )
}
