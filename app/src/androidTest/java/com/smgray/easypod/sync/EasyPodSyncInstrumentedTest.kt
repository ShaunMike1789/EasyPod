package com.smgray.easypod.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smgray.easypod.EasyPodApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EasyPodSyncInstrumentedTest {
    @Test
    fun backupFingerprintIsStableWithoutDatabaseChanges() = runBlocking {
        val application =
            ApplicationProvider.getApplicationContext<EasyPodApplication>()

        val first = application.container.backupService.fingerprint()
        val second = application.container.backupService.fingerprint()

        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun credentialStoreRoundTripsThroughAndroidKeystore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(
            "easypod_sync_instrumented_test",
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        val store = SyncCredentialStore(preferences)
        try {
            store.write("correct horse battery staple")

            assertTrue(store.hasValue())
            assertEquals("correct horse battery staple", store.read())
        } finally {
            store.clear()
            preferences.edit().clear().commit()
        }
    }
}
