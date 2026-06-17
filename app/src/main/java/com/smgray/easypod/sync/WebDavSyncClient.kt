package com.smgray.easypod.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

internal data class RemoteSnapshot(
    val etag: String,
)

internal class SyncConflictException(message: String) : IOException(message)

internal class WebDavSyncClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun inspect(endpoint: String, credentials: SyncCredentials): RemoteSnapshot? {
        val request = requestBuilder(endpoint, credentials)
            .head()
            .build()
        client.newCall(request).execute().use { response ->
            return when {
                response.code == 404 -> null
                response.isSuccessful -> RemoteSnapshot(
                    etag = requireStrongEtag(response.header("ETag")),
                )
                else -> throw responseException("inspect", response.code)
            }
        }
    }

    fun download(
        endpoint: String,
        credentials: SyncCredentials,
        destination: File,
    ): RemoteSnapshot {
        val request = requestBuilder(endpoint, credentials).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw responseException("download", response.code)
            }
            val body = requireNotNull(response.body) {
                "The WebDAV server returned an empty backup"
            }
            val declaredLength = body.contentLength()
            require(declaredLength < 0 || declaredLength <= MAX_BACKUP_BYTES) {
                "The remote backup is larger than EasyPod supports"
            }
            FileOutputStream(destination).buffered().use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BACKUP_BYTES) {
                            "The remote backup is larger than EasyPod supports"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return RemoteSnapshot(
                etag = requireStrongEtag(response.header("ETag")),
            )
        }
    }

    fun upload(
        endpoint: String,
        credentials: SyncCredentials,
        source: File,
        expectedEtag: String?,
    ): RemoteSnapshot {
        require(source.length() <= MAX_BACKUP_BYTES) {
            "The EasyPod backup is too large to sync"
        }
        val builder = requestBuilder(endpoint, credentials)
            .put(source.asRequestBody(BACKUP_MEDIA_TYPE))
        if (expectedEtag == null) {
            builder.header("If-None-Match", "*")
        } else {
            builder.header("If-Match", expectedEtag)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 412) {
                throw SyncConflictException(
                    "The remote backup changed during sync. Try again.",
                )
            }
            if (!response.isSuccessful) {
                throw responseException("upload", response.code)
            }
        }
        return requireNotNull(inspect(endpoint, credentials)) {
            "The WebDAV server did not retain the uploaded backup"
        }
    }

    private fun requestBuilder(
        endpoint: String,
        credentials: SyncCredentials,
    ): Request.Builder = Request.Builder()
        .url(endpoint.toHttpUrl())
        .header("Accept", "application/zip")
        .header("User-Agent", "EasyPod Android")
        .apply {
            basicAuthHeader(credentials)?.let { header("Authorization", it) }
        }

    private fun requireStrongEtag(value: String?): String {
        require(!value.isNullOrBlank()) {
            "This WebDAV server does not provide ETags required for safe sync"
        }
        require(!value.startsWith("W/", ignoreCase = true)) {
            "This WebDAV server only provides weak ETags, which are unsafe for sync"
        }
        return value
    }

    private fun responseException(operation: String, code: Int): IOException {
        val message = when (code) {
            401, 403 -> "WebDAV authentication was rejected"
            in 300..399 -> "WebDAV redirects are not followed; use the final HTTPS URL"
            405 -> "The server does not support the required WebDAV operation"
            507 -> "The WebDAV server is out of storage"
            else -> "WebDAV $operation failed with HTTP $code"
        }
        return IOException(message)
    }

    private companion object {
        val BACKUP_MEDIA_TYPE = "application/zip".toMediaType()
        const val MAX_BACKUP_BYTES = 100L * 1024L * 1024L
    }
}
