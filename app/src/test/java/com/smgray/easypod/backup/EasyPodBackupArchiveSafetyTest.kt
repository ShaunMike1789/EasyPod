package com.smgray.easypod.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyPodBackupArchiveSafetyTest {
    @Test
    fun acceptsOnlyFlatArchiveEntryNames() {
        assertTrue(EasyPodBackupArchiveSafety.isSafeEntryName("library.json"))
        assertFalse(EasyPodBackupArchiveSafety.isSafeEntryName("../library.json"))
        assertFalse(EasyPodBackupArchiveSafety.isSafeEntryName("folder/library.json"))
        assertFalse(EasyPodBackupArchiveSafety.isSafeEntryName("folder\\library.json"))
    }

    @Test
    fun copiesContentWithinLimit() {
        val source = "EasyPod".toByteArray()
        val destination = ByteArrayOutputStream()

        val copied = EasyPodBackupArchiveSafety.copyLimited(
            ByteArrayInputStream(source),
            destination,
            source.size.toLong(),
        )

        assertTrue(copied == source.size.toLong())
        assertArrayEquals(source, destination.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsContentBeyondLimit() {
        EasyPodBackupArchiveSafety.copyLimited(
            ByteArrayInputStream(ByteArray(5)),
            ByteArrayOutputStream(),
            4,
        )
    }
}
