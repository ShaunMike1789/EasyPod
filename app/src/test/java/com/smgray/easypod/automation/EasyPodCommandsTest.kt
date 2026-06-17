package com.smgray.easypod.automation

import org.junit.Assert.assertTrue
import org.junit.Test

class EasyPodCommandsTest {
    @Test
    fun exposesPlaybackAutomationActions() {
        assertTrue(EasyPodCommands.ACTION_PLAY in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_PAUSE in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_PLAY_PAUSE in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_PLAY_NEXT in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_PLAY_PREVIOUS in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_SKIP_FORWARD in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_SKIP_BACKWARD in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_SKIP_TO_END in EasyPodCommands.SupportedActions)
    }

    @Test
    fun exposesSmartPlayAndSyncAutomationActions() {
        assertTrue(EasyPodCommands.ACTION_UPDATE_SMART_PLAY in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_START_SMART_PLAY in EasyPodCommands.SupportedActions)
        assertTrue(EasyPodCommands.ACTION_START_SYNC in EasyPodCommands.SupportedActions)
    }
}
