package com.smgray.easypod

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingSearchIntentsTest {
    @Test
    fun extractsQueryFromAndroidSearchIntent() {
        assertEquals(
            "science news",
            IncomingSearchIntents.queryFrom(
                action = Intent.ACTION_SEARCH,
                searchQuery = " science news ",
                extraText = null,
                fallbackQuery = null,
            ),
        )
    }

    @Test
    fun extractsQueryFromGoogleSearchAction() {
        assertEquals(
            "daily briefing",
            IncomingSearchIntents.queryFrom(
                action = IncomingSearchIntents.GOOGLE_SEARCH_ACTION,
                searchQuery = null,
                extraText = "daily briefing",
                fallbackQuery = null,
            ),
        )
    }

    @Test
    fun ignoresNonSearchIntents() {
        assertNull(
            IncomingSearchIntents.queryFrom(
                action = Intent.ACTION_VIEW,
                searchQuery = "ignored",
                extraText = null,
                fallbackQuery = null,
            ),
        )
    }
}
