package com.smgray.easypod.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyPodMediaLibraryTest {
    @Test
    fun feedIdsRoundTrip() {
        val feedId = "feed-123"

        assertEquals(feedId, EasyPodMediaIds.feedId(EasyPodMediaIds.feed(feedId)))
        assertNull(EasyPodMediaIds.feedId(EasyPodMediaIds.UNPLAYED))
    }

    @Test
    fun pagesItemsWithoutOverflowing() {
        val items = (0 until 10).toList()

        assertEquals(listOf(3, 4, 5), pageMediaItems(items, page = 1, pageSize = 3))
        assertEquals(listOf(9), pageMediaItems(items, page = 3, pageSize = 3))
        assertEquals(emptyList<Int>(), pageMediaItems(items, page = 4, pageSize = 3))
        assertNull(pageMediaItems(items, page = -1, pageSize = 3))
        assertNull(pageMediaItems(items, page = 0, pageSize = 0))
    }

    @Test
    fun acceptsEasyPodTrustedSystemAndLegacyControllers() {
        assertTrue(
            shouldAcceptMediaController(
                controllerPackage = "com.smgray.easypod",
                isTrusted = false,
                isSystemApplication = false,
                isLegacyController = false,
            ),
        )
        assertTrue(
            shouldAcceptMediaController(
                controllerPackage = "com.google.android.projection.gearhead",
                isTrusted = true,
                isSystemApplication = false,
                isLegacyController = false,
            ),
        )
        assertTrue(
            shouldAcceptMediaController(
                controllerPackage = "com.android.systemui",
                isTrusted = false,
                isSystemApplication = true,
                isLegacyController = false,
            ),
        )
        assertTrue(
            shouldAcceptMediaController(
                controllerPackage = "android.media.session.MediaController",
                isTrusted = false,
                isSystemApplication = false,
                isLegacyController = true,
            ),
        )
        assertFalse(
            shouldAcceptMediaController(
                controllerPackage = "example.untrusted.controller",
                isTrusted = false,
                isSystemApplication = false,
                isLegacyController = false,
            ),
        )
    }
}
