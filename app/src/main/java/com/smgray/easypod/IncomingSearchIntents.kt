package com.smgray.easypod

import android.app.SearchManager
import android.content.Intent

object IncomingSearchIntents {
    const val GOOGLE_SEARCH_ACTION = "com.google.android.gms.actions.SEARCH_ACTION"

    fun queryFrom(intent: Intent): String? {
        return queryFrom(
            action = intent.action,
            searchQuery = intent.getStringExtra(SearchManager.QUERY),
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
            fallbackQuery = intent.getStringExtra(EXTRA_QUERY),
        )
    }

    internal fun queryFrom(
        action: String?,
        searchQuery: String?,
        extraText: String?,
        fallbackQuery: String?,
    ): String? {
        if (action != Intent.ACTION_SEARCH && action != GOOGLE_SEARCH_ACTION) {
            return null
        }
        return listOf(
            searchQuery,
            extraText,
            fallbackQuery,
        ).firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    private const val EXTRA_QUERY = "query"
}
