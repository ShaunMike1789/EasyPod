package com.smgray.easypod.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyCategoryCodecTest {
    @Test
    fun parsesCategoryCatalogAndColor() {
        assertEquals(
            listOf(
                LegacyCategory("News", -16776961),
                LegacyCategory("Technology", 42),
            ),
            LegacyCategoryCodec.parseCatalog("News^-16776961|Technology^42"),
        )
    }

    @Test
    fun preservesTwoAssignmentSlots() {
        assertEquals(
            listOf("News", "Daily"),
            LegacyCategoryCodec.parseAssignments(" News | Daily | Ignored "),
        )
    }
}

