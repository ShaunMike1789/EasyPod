package com.smgray.easypod.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavSyncClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun normalizeEndpointRequiresFullHttpsFileUrl() {
        assertEquals(
            "https://cloud.example/files/easypod.zip",
            normalizeSyncEndpoint(" https://cloud.example/files/easypod.zip "),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSyncEndpoint("http://cloud.example/files/easypod.zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSyncEndpoint("https://user:secret@cloud.example/easypod.zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSyncEndpoint("https://cloud.example/files/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSyncEndpoint("https://cloud.example/easypod.zip?token=secret")
        }
    }

    @Test
    fun inspectUsesBasicAuthAndRequiresStrongEtag() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"remote-1\""),
        )

        val snapshot = WebDavSyncClient().inspect(
            endpoint(),
            SyncCredentials("shaun", "app-password"),
        )

        assertEquals("\"remote-1\"", snapshot?.etag)
        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals(
            "Basic c2hhdW46YXBwLXBhc3N3b3Jk",
            request.getHeader("Authorization"),
        )
    }

    @Test
    fun newUploadUsesIfNoneMatchThenRefreshesEtag() {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"remote-2\""),
        )
        val source = temporaryFolder.newFile("backup.zip").apply {
            writeText("backup")
        }

        val snapshot = WebDavSyncClient().upload(
            endpoint(),
            SyncCredentials("", null),
            source,
            expectedEtag = null,
        )

        assertEquals("\"remote-2\"", snapshot.etag)
        val put = server.takeRequest()
        val head = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals(null, put.getHeader("If-Match"))
        assertEquals("HEAD", head.method)
    }

    @Test
    fun existingUploadUsesIfMatchAndRejectsRace() {
        server.enqueue(MockResponse().setResponseCode(412))
        val source = temporaryFolder.newFile("backup.zip").apply {
            writeText("backup")
        }

        val error = assertThrows(SyncConflictException::class.java) {
            WebDavSyncClient().upload(
                endpoint(),
                SyncCredentials("", null),
                source,
                expectedEtag = "\"remote-1\"",
            )
        }

        assertTrue(error.message.orEmpty().contains("changed during sync"))
        assertEquals("\"remote-1\"", server.takeRequest().getHeader("If-Match"))
    }

    private fun endpoint(): String =
        server.url("/easypod.zip").toString()
}
