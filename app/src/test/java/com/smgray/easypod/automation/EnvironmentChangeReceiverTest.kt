package com.smgray.easypod.automation

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentChangeReceiverTest {
    @Test
    fun supportsBootAndTimezoneScheduleTriggers() {
        assertTrue(
            Intent.ACTION_BOOT_COMPLETED in EnvironmentChangeReceiver.SupportedActions,
        )
        assertTrue(
            Intent.ACTION_TIMEZONE_CHANGED in EnvironmentChangeReceiver.SupportedActions,
        )
        assertTrue(
            Intent.ACTION_MY_PACKAGE_REPLACED in EnvironmentChangeReceiver.SupportedActions,
        )
    }

    @Test
    fun ignoresUnrelatedBroadcasts() {
        assertFalse(Intent.ACTION_AIRPLANE_MODE_CHANGED in EnvironmentChangeReceiver.SupportedActions)
    }
}
