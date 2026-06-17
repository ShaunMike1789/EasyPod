package com.smgray.easypod.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyBackupManifestParserTest {
    @Test
    fun parsesCurrentManifestLabels() {
        val manifest = LegacyBackupManifestParser.parse(
            """
            # Version: 4.3.321
            [Version]
            40333
            [timeStamp]
            Thu, 21 Jan 2021 12:00:00 GMT
            [RootPath]
            /storage/emulated/0/PodcastArchive
            [FileNum]
            2
            [totalSize]
            579
            [FileNames]
            Settings.xml.autobak,library.db.autobak
            [FileSizes]
            123,456
            [device]
            Test Device
            """.trimIndent(),
        )

        assertEquals("40333", manifest.version)
        assertEquals(579L, manifest.totalSize)
        assertEquals(2, manifest.files.size)
        assertEquals("library.db.autobak", manifest.files[1].name)
        assertEquals(456L, manifest.files[1].size)
    }

    @Test
    fun rejectsInconsistentFileLists() {
        assertThrows(IllegalArgumentException::class.java) {
            LegacyBackupManifestParser.parse(
                """
                [FileNum]
                2
                [FileNames]
                one
                [FileSizes]
                1
                """.trimIndent(),
            )
        }
    }
}
