package com.smgray.easypod.smartplay

import com.smgray.easypod.data.SmartPlayCandidate
import com.smgray.easypod.downloads.DownloadState
import com.smgray.easypod.media.EpisodeMediaClassifier
import com.smgray.easypod.media.EpisodeMediaType
import kotlin.random.Random

data class SmartPlayRuleSpec(
    val episodeCount: Int,
    val mediaType: Int,
    val sortMode: Int,
)

object SmartPlayRuleEngine {
    const val MEDIA_ANY = -2
    const val MEDIA_AUDIO = 1
    const val MEDIA_VIDEO = 2
    const val MEDIA_IMAGE = 3

    const val SORT_OLDEST = 2
    const val SORT_LATEST = 3
    const val SORT_RANDOM = 100

    fun select(
        rule: SmartPlayRuleSpec,
        candidates: List<SmartPlayCandidate>,
        excludedEpisodeIds: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): List<SmartPlayCandidate> {
        val eligible = candidates.filter { candidate ->
            candidate.id !in excludedEpisodeIds &&
                matchesMediaType(candidate, rule.mediaType) &&
                isAvailable(candidate)
        }
        val sorted = when (rule.sortMode) {
            SORT_OLDEST -> eligible.sortedWith(
                compareBy<SmartPlayCandidate> { it.publishedAt ?: Long.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )

            SORT_RANDOM -> eligible.shuffled(random)
            else -> eligible.sortedWith(
                compareByDescending<SmartPlayCandidate> {
                    it.publishedAt ?: Long.MIN_VALUE
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
        }
        return if (rule.episodeCount > 0) {
            sorted.take(rule.episodeCount)
        } else {
            sorted
        }
    }

    private fun isAvailable(candidate: SmartPlayCandidate): Boolean {
        if (candidate.localDownloadPath != null) return true
        if (candidate.mediaUrl.isNullOrBlank()) return false
        if (candidate.feedDownloadAction !in AUTO_DOWNLOAD_ACTIONS) return true
        return candidate.downloadState in STARTED_DOWNLOAD_STATES
    }

    private fun matchesMediaType(
        candidate: SmartPlayCandidate,
        requiredType: Int,
    ): Boolean {
        if (requiredType == MEDIA_ANY) return true
        val actualType = when (
            EpisodeMediaClassifier.classify(
                candidate.mimeType,
                candidate.mediaUrl ?: candidate.localDownloadPath,
            )
        ) {
            EpisodeMediaType.Audio -> MEDIA_AUDIO
            EpisodeMediaType.Video -> MEDIA_VIDEO
            EpisodeMediaType.Image -> MEDIA_IMAGE
        }
        return actualType == requiredType
    }

    private val AUTO_DOWNLOAD_ACTIONS = setOf(1, 4)
    private val STARTED_DOWNLOAD_STATES = setOf(
        DownloadState.QUEUED,
        DownloadState.RUNNING,
        DownloadState.COMPLETE,
    )
}
