package com.smgray.easypod.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smgray.easypod.EasyPodApplication
import com.smgray.easypod.smartplay.SmartPlayRuleEngine
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartPlayInstrumentedTest {
    @Test
    fun multiRulePlaylistRoundTripsInEditorOrder() = runBlocking {
        val application =
            ApplicationProvider.getApplicationContext<EasyPodApplication>()
        val repository = application.container.repository
        val dao = application.container.database.smartPlayDao()
        val testName = "Instrumented ${UUID.randomUUID()}"
        var playlistId: Long? = null

        try {
            playlistId = repository.saveSmartPlaylist(
                playlistId = null,
                name = testName,
                rules = listOf(
                    SmartPlayRuleDraft(
                        episodeCount = 3,
                        mediaType = SmartPlayRuleEngine.MEDIA_AUDIO,
                        sortMode = SmartPlayRuleEngine.SORT_OLDEST,
                    ),
                    SmartPlayRuleDraft(
                        episodeCount = 20,
                        mediaType = SmartPlayRuleEngine.MEDIA_VIDEO,
                        sortMode = SmartPlayRuleEngine.SORT_RANDOM,
                    ),
                ),
            )

            val created = dao.getRules(playlistId)
            assertEquals(listOf(0, 1), created.map(SmartPlayRuleSummary::position))
            assertEquals(listOf(3, 20), created.map(SmartPlayRuleSummary::episodeCount))
            assertEquals(
                listOf(
                    SmartPlayRuleEngine.MEDIA_AUDIO,
                    SmartPlayRuleEngine.MEDIA_VIDEO,
                ),
                created.map(SmartPlayRuleSummary::mediaType),
            )
            assertEquals(
                listOf(
                    SmartPlayRuleEngine.SORT_OLDEST,
                    SmartPlayRuleEngine.SORT_RANDOM,
                ),
                created.map(SmartPlayRuleSummary::sortMode),
            )

            repository.saveSmartPlaylist(
                playlistId = playlistId,
                name = "$testName updated",
                rules = listOf(
                    SmartPlayRuleDraft(
                        episodeCount = 50,
                        mediaType = SmartPlayRuleEngine.MEDIA_ANY,
                        sortMode = SmartPlayRuleEngine.SORT_LATEST,
                    ),
                ),
            )

            val updated = dao.getRules(playlistId)
            assertEquals(1, updated.size)
            assertEquals("$testName updated", updated.single().name)
            assertEquals(50, updated.single().episodeCount)
        } finally {
            playlistId?.let { repository.deleteSmartPlaylist(it) }
        }
    }
}
