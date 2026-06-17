package com.smgray.easypod.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EasyPodBackupManifestCodecTest {
    @Test
    fun roundTripsVersionedManifest() {
        val expected = EasyPodBackupManifest(
            formatVersion = 1,
            databaseSchemaVersion = 4,
            createdAt = 1_781_450_000_000,
            dataEntry = EasyPodBackupManifestCodec.DATA_ENTRY,
            rowCount = 42,
            tables = listOf("feeds", "episodes"),
            mediaIncluded = false,
        )

        val parsed = EasyPodBackupManifestCodec.parse(
            EasyPodBackupManifestCodec.encode(expected),
        )

        assertEquals(expected, parsed)
        assertFalse(parsed.mediaIncluded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeTableName() {
        EasyPodBackupManifestCodec.parse(
            """
            format=easypod-backup
            formatVersion=1
            databaseSchemaVersion=4
            createdAt=1781450000000
            dataEntry=library.json
            rowCount=1
            tables=feeds,../episodes
            mediaIncluded=false
            """.trimIndent(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFutureFormat() {
        EasyPodBackupManifestCodec.parse(
            """
            format=easypod-backup
            formatVersion=99
            databaseSchemaVersion=4
            createdAt=1781450000000
            dataEntry=library.json
            rowCount=1
            tables=feeds
            mediaIncluded=false
            """.trimIndent(),
        )
    }
}
