package com.smgray.easypod.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlanTest {
    @Test
    fun missingRemoteCreatesIt() {
        assertEquals(
            SyncPlan.CREATE_REMOTE,
            chooseSyncPlan(null, "local-2", "remote-1", "local-1"),
        )
    }

    @Test
    fun existingRemoteNeedsExplicitFirstSyncChoice() {
        assertEquals(
            SyncPlan.NEEDS_RESOLUTION,
            chooseSyncPlan("remote-1", "local-1", null, null),
        )
    }

    @Test
    fun oneSidedChangesCanSyncAutomatically() {
        assertEquals(
            SyncPlan.UPLOAD_LOCAL,
            chooseSyncPlan("remote-1", "local-2", "remote-1", "local-1"),
        )
        assertEquals(
            SyncPlan.DOWNLOAD_AND_MERGE,
            chooseSyncPlan("remote-2", "local-1", "remote-1", "local-1"),
        )
        assertEquals(
            SyncPlan.UP_TO_DATE,
            chooseSyncPlan("remote-1", "local-1", "remote-1", "local-1"),
        )
    }

    @Test
    fun twoSidedChangesNeverPickAWinnerSilently() {
        assertEquals(
            SyncPlan.NEEDS_RESOLUTION,
            chooseSyncPlan("remote-2", "local-2", "remote-1", "local-1"),
        )
    }
}
