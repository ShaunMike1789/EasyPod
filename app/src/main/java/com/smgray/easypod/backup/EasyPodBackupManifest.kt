package com.smgray.easypod.backup

import java.io.StringReader
import java.util.Properties

data class EasyPodBackupManifest(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val createdAt: Long,
    val dataEntry: String,
    val rowCount: Int,
    val tables: List<String>,
    val mediaIncluded: Boolean,
)

object EasyPodBackupManifestCodec {
    const val FORMAT_NAME = "easypod-backup"
    const val CURRENT_FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.properties"
    const val DATA_ENTRY = "library.json"

    fun encode(manifest: EasyPodBackupManifest): String = buildString {
        appendLine("format=$FORMAT_NAME")
        appendLine("formatVersion=${manifest.formatVersion}")
        appendLine("databaseSchemaVersion=${manifest.databaseSchemaVersion}")
        appendLine("createdAt=${manifest.createdAt}")
        appendLine("dataEntry=${manifest.dataEntry}")
        appendLine("rowCount=${manifest.rowCount}")
        appendLine("tables=${manifest.tables.joinToString(",")}")
        appendLine("mediaIncluded=${manifest.mediaIncluded}")
    }

    fun parse(text: String): EasyPodBackupManifest {
        val properties = Properties().apply {
            load(StringReader(text))
        }
        require(properties.getProperty("format") == FORMAT_NAME) {
            "This is not a EasyPod backup"
        }
        val formatVersion = properties.requiredInt("formatVersion")
        require(formatVersion in 1..CURRENT_FORMAT_VERSION) {
            "This backup format is newer than this version of EasyPod"
        }
        val schemaVersion = properties.requiredInt("databaseSchemaVersion")
        require(schemaVersion > 0) { "Backup database version is invalid" }
        val createdAt = properties.requiredLong("createdAt")
        require(createdAt > 0) { "Backup creation time is invalid" }
        val dataEntry = properties.required("dataEntry")
        require(dataEntry == DATA_ENTRY) { "Backup data entry is not supported" }
        val rowCount = properties.requiredInt("rowCount")
        require(rowCount in 0..MAX_BACKUP_ROWS) {
            "Backup row count is outside the supported range"
        }
        val tables = properties.required("tables")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        require(tables.isNotEmpty() && tables.size == tables.distinct().size) {
            "Backup table list is invalid"
        }
        require(tables.all { TABLE_NAME.matches(it) }) {
            "Backup contains an invalid table name"
        }
        val mediaIncluded = properties.required("mediaIncluded").toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Backup media flag is invalid")

        return EasyPodBackupManifest(
            formatVersion = formatVersion,
            databaseSchemaVersion = schemaVersion,
            createdAt = createdAt,
            dataEntry = dataEntry,
            rowCount = rowCount,
            tables = tables,
            mediaIncluded = mediaIncluded,
        )
    }

    private fun Properties.required(name: String): String =
        getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Backup manifest is missing $name")

    private fun Properties.requiredInt(name: String): Int =
        required(name).toIntOrNull()
            ?: throw IllegalArgumentException("Backup manifest $name is invalid")

    private fun Properties.requiredLong(name: String): Long =
        required(name).toLongOrNull()
            ?: throw IllegalArgumentException("Backup manifest $name is invalid")

    private val TABLE_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    const val MAX_BACKUP_ROWS = 250_000
}
