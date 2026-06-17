package com.smgray.easypod.sync

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class EasyPodSyncSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
    val intervalHours: Int = 24,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastStatus: String = "Not configured",
    val lastMessage: String? = null,
    internal val lastRemoteEtag: String? = null,
    internal val lastLocalFingerprint: String? = null,
)

internal data class SyncCredentials(
    val username: String,
    val password: String?,
)

internal class EasyPodSyncSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val credentialStore = SyncCredentialStore(preferences)
    private val mutableSettings = MutableStateFlow(read())

    val settings: StateFlow<EasyPodSyncSettings> = mutableSettings

    fun saveConfiguration(
        enabled: Boolean,
        endpoint: String,
        username: String,
        password: String?,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ): EasyPodSyncSettings {
        val normalizedEndpoint = endpoint
            .takeIf(String::isNotBlank)
            ?.let(::normalizeSyncEndpoint)
            .orEmpty()
        require(!enabled || normalizedEndpoint.isNotEmpty()) {
            "Enter an HTTPS WebDAV file URL before enabling sync"
        }
        val normalizedUsername = username.trim()
        val current = mutableSettings.value
        val destinationChanged =
            normalizedEndpoint != current.endpoint ||
                normalizedUsername != current.username

        when {
            normalizedUsername.isEmpty() -> credentialStore.clear()
            password != null -> credentialStore.write(password)
        }

        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_ENDPOINT, normalizedEndpoint)
            .putString(KEY_USERNAME, normalizedUsername)
            .putInt(KEY_INTERVAL_HOURS, intervalHours.coerceIn(1, 168))
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .putBoolean(KEY_CHARGING_ONLY, chargingOnly)
            .apply {
                if (destinationChanged) {
                    remove(KEY_LAST_REMOTE_ETAG)
                    remove(KEY_LAST_LOCAL_FINGERPRINT)
                    remove(KEY_LAST_SYNC_AT)
                    putString(KEY_LAST_STATUS, "Not yet synced")
                    remove(KEY_LAST_MESSAGE)
                }
            }
            .apply()
        return refresh()
    }

    fun updateResult(
        status: String,
        message: String?,
        syncedAt: Long? = mutableSettings.value.lastSyncAt,
        remoteEtag: String? = mutableSettings.value.lastRemoteEtag,
        localFingerprint: String? = mutableSettings.value.lastLocalFingerprint,
    ) {
        preferences.edit()
            .putString(KEY_LAST_STATUS, status)
            .apply {
                putNullableString(KEY_LAST_MESSAGE, message)
                putNullableLong(KEY_LAST_SYNC_AT, syncedAt)
                putNullableString(KEY_LAST_REMOTE_ETAG, remoteEtag)
                putNullableString(KEY_LAST_LOCAL_FINGERPRINT, localFingerprint)
            }
            .apply()
        refresh()
    }

    fun credentials(): SyncCredentials = SyncCredentials(
        username = mutableSettings.value.username,
        password = credentialStore.read(),
    )

    private fun refresh(): EasyPodSyncSettings =
        read().also { mutableSettings.value = it }

    private fun read(): EasyPodSyncSettings = EasyPodSyncSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        hasPassword = credentialStore.hasValue(),
        intervalHours = preferences.getInt(KEY_INTERVAL_HOURS, 24),
        wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true),
        chargingOnly = preferences.getBoolean(KEY_CHARGING_ONLY, false),
        lastSyncAt = preferences.getNullableLong(KEY_LAST_SYNC_AT),
        lastStatus = preferences.getString(KEY_LAST_STATUS, "Not configured")
            ?: "Not configured",
        lastMessage = preferences.getString(KEY_LAST_MESSAGE, null),
        lastRemoteEtag = preferences.getString(KEY_LAST_REMOTE_ETAG, null),
        lastLocalFingerprint =
            preferences.getString(KEY_LAST_LOCAL_FINGERPRINT, null),
    )

    private fun SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ) {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun SharedPreferences.getNullableLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private companion object {
        const val PREFERENCES_NAME = "easypod_sync"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_USERNAME = "username"
        const val KEY_INTERVAL_HOURS = "interval_hours"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_CHARGING_ONLY = "charging_only"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_STATUS = "last_status"
        const val KEY_LAST_MESSAGE = "last_message"
        const val KEY_LAST_REMOTE_ETAG = "last_remote_etag"
        const val KEY_LAST_LOCAL_FINGERPRINT = "last_local_fingerprint"
    }
}

internal fun normalizeSyncEndpoint(value: String): String {
    val url = requireNotNull(value.trim().toHttpUrlOrNull()) {
        "The WebDAV URL is invalid"
    }
    require(url.scheme == "https") { "WebDAV sync requires HTTPS" }
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "Put the username and password in their separate fields"
    }
    require(url.fragment == null) { "The WebDAV URL cannot contain a fragment" }
    require(url.query == null) { "The WebDAV URL cannot contain a query" }
    require(url.encodedPath != "/" && !url.encodedPath.endsWith("/")) {
        "Enter the full WebDAV backup file URL, including its filename"
    }
    return url.withoutTrailingDefaultPort().toString()
}

private fun HttpUrl.withoutTrailingDefaultPort(): HttpUrl = newBuilder().build()

internal fun basicAuthHeader(credentials: SyncCredentials): String? =
    credentials.username.takeIf(String::isNotEmpty)?.let { username ->
        okhttp3.Credentials.basic(
            username,
            credentials.password.orEmpty(),
            StandardCharsets.UTF_8,
        )
    }
