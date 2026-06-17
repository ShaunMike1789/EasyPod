package com.smgray.easypod.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.smgray.easypod.data.EASYPOD_DATABASE_VERSION
import com.smgray.easypod.data.EasyPodDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface BackupValue {
    data object Null : BackupValue
    data class Integer(val value: Long) : BackupValue
    data class Real(val value: Double) : BackupValue
    data class Text(val value: String) : BackupValue
    data class Boolean(val value: kotlin.Boolean) : BackupValue
}

private fun SupportSQLiteStatement.bindBackupValue(index: Int, value: BackupValue) {
    when (value) {
        BackupValue.Null -> bindNull(index)
        is BackupValue.Integer -> bindLong(index, value.value)
        is BackupValue.Real -> bindDouble(index, value.value)
        is BackupValue.Text -> bindString(index, value.value)
        is BackupValue.Boolean -> bindLong(index, if (value.value) 1 else 0)
    }
}

private fun backupEpisodeIdentityKey(feedId: String?, mediaUrl: String?): String? =
    mediaUrl?.let { "${feedId.orEmpty()}\u0000$it" }

data class EasyPodBackupExportResult(
    val feeds: Int,
    val episodes: Int,
    val rows: Int,
)

data class EasyPodBackupRestoreResult(
    val feeds: Int,
    val episodes: Int,
    val rows: Int,
)

class EasyPodBackupService(
    context: Context,
    private val database: EasyPodDatabase,
) {
    private val appContext = context.applicationContext

    suspend fun export(uri: Uri): EasyPodBackupExportResult = withContext(Dispatchers.IO) {
        val output = requireNotNull(
            appContext.contentResolver.openOutputStream(uri, "w"),
        ) {
            "The selected backup destination could not be opened"
        }
        output.buffered().use { exportTo(it) }
    }

    suspend fun export(file: File): EasyPodBackupExportResult = withContext(Dispatchers.IO) {
        FileOutputStream(file).buffered().use { exportTo(it) }
    }

    suspend fun fingerprint(): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(DiscardingOutputStream, digest).use {
                writeDatabaseJson(it)
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }

    private suspend fun exportTo(output: OutputStream): EasyPodBackupExportResult {
        withTemporaryDirectory("export") { directory ->
            val dataFile = File(directory, EasyPodBackupManifestCodec.DATA_ENTRY)
            val counts = database.withTransaction {
                FileOutputStream(dataFile).buffered().use { output ->
                    writeDatabaseJson(output)
                }
            }
            val manifest = EasyPodBackupManifest(
                formatVersion = EasyPodBackupManifestCodec.CURRENT_FORMAT_VERSION,
                databaseSchemaVersion = EASYPOD_DATABASE_VERSION,
                createdAt = System.currentTimeMillis(),
                dataEntry = EasyPodBackupManifestCodec.DATA_ENTRY,
                rowCount = counts.values.sum(),
                tables = TABLES.map(TableSpec::name),
                mediaIncluded = false,
            )
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(EasyPodBackupManifestCodec.MANIFEST_ENTRY))
                zip.write(
                    EasyPodBackupManifestCodec.encode(manifest)
                        .toByteArray(StandardCharsets.UTF_8),
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(EasyPodBackupManifestCodec.DATA_ENTRY))
                FileInputStream(dataFile).buffered().use { data ->
                    data.copyTo(zip)
                }
                zip.closeEntry()
            }
            return EasyPodBackupExportResult(
                feeds = counts["feeds"] ?: 0,
                episodes = counts["episodes"] ?: 0,
                rows = manifest.rowCount,
            )
        }
    }

    suspend fun restore(uri: Uri): EasyPodBackupRestoreResult = withContext(Dispatchers.IO) {
        val input = requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "The selected backup could not be opened"
        }
        input.buffered().use { restoreFrom(it) }
    }

    suspend fun restore(file: File): EasyPodBackupRestoreResult = withContext(Dispatchers.IO) {
        FileInputStream(file).buffered().use { restoreFrom(it) }
    }

    private suspend fun restoreFrom(input: InputStream): EasyPodBackupRestoreResult {
        withTemporaryDirectory("restore") { directory ->
            val archive = File(directory, "backup.zip")
            FileOutputStream(archive).buffered().use { destination ->
                EasyPodBackupArchiveSafety.copyLimited(
                    input,
                    destination,
                    MAX_ARCHIVE_BYTES,
                )
            }
            val extracted = extractArchive(archive, directory)
            val manifest = EasyPodBackupManifestCodec.parse(
                extracted.manifest.readText(StandardCharsets.UTF_8),
            )
            require(manifest.databaseSchemaVersion <= EASYPOD_DATABASE_VERSION) {
                "This backup was created by a newer EasyPod database"
            }
            require(!manifest.mediaIncluded) {
                "Backups containing media are not supported"
            }
            require(manifest.tables == TABLES.map(TableSpec::name)) {
                "Backup table list is not supported"
            }

            val counts = database.withTransaction {
                FileInputStream(extracted.data).buffered().use { data ->
                    restoreDatabaseJson(data, manifest)
                }
            }
            return EasyPodBackupRestoreResult(
                feeds = counts["feeds"] ?: 0,
                episodes = counts["episodes"] ?: 0,
                rows = counts.values.sum(),
            )
        }
    }

    private fun writeDatabaseJson(output: OutputStream): Map<String, Int> {
        val db = database.openHelper.writableDatabase
        val counts = linkedMapOf<String, Int>()
        JsonWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            writer.beginObject()
            writer.name("formatVersion")
                .value(EasyPodBackupManifestCodec.CURRENT_FORMAT_VERSION.toLong())
            writer.name("databaseSchemaVersion").value(EASYPOD_DATABASE_VERSION.toLong())
            writer.name("tables").beginArray()
            TABLES.forEach { table ->
                writer.beginObject()
                writer.name("name").value(table.name)
                writer.name("rows").beginArray()
                val orderBy = table.primaryKeys.joinToString(", ") { "`$it`" }
                val sql = "SELECT ${table.quotedColumns()} FROM `${table.name}` " +
                    "ORDER BY $orderBy"
                var count = 0
                db.query(sql).use { cursor ->
                    while (cursor.moveToNext()) {
                        writer.beginObject()
                        table.columns.forEachIndexed { index, column ->
                            writer.name(column)
                            when {
                                table.name == "playback_state" &&
                                    column == "sleepTimerEndsAt" -> writer.nullValue()

                                table.name == "playback_state" &&
                                    column == "sleepAtEpisodeEnd" -> writer.value(false)

                                else -> cursor.writeValue(writer, index)
                            }
                        }
                        writer.endObject()
                        count++
                    }
                }
                writer.endArray()
                writer.endObject()
                counts[table.name] = count
            }
            writer.endArray()
            writer.endObject()
        }
        return counts
    }

    private fun restoreDatabaseJson(
        input: InputStream,
        manifest: EasyPodBackupManifest,
    ): Map<String, Int> {
        val db = database.openHelper.writableDatabase
        val identities = RestoreIdentities.load(db)
        val restoredFeedIds = linkedSetOf<String>()
        val counts = linkedMapOf<String, Int>()
        var totalRows = 0

        JsonReader(input.reader(StandardCharsets.UTF_8)).use { reader ->
            reader.beginObject()
            require(reader.nextName() == "formatVersion") { "Backup JSON header is invalid" }
            require(reader.nextInt() == manifest.formatVersion) {
                "Backup JSON format does not match its manifest"
            }
            require(reader.nextName() == "databaseSchemaVersion") {
                "Backup JSON database header is invalid"
            }
            require(reader.nextInt() == manifest.databaseSchemaVersion) {
                "Backup JSON database version does not match its manifest"
            }
            require(reader.nextName() == "tables") { "Backup JSON table section is missing" }
            reader.beginArray()

            TABLES.forEach { table ->
                require(reader.hasNext()) { "Backup is missing table ${table.name}" }
                reader.beginObject()
                require(reader.nextName() == "name") { "Backup table header is invalid" }
                require(reader.nextString() == table.name) {
                    "Backup tables are not in the supported order"
                }
                require(reader.nextName() == "rows") {
                    "Backup table ${table.name} has no row section"
                }

                if (table.replaceBeforeRestore) {
                    db.execSQL("DELETE FROM `${table.name}`")
                }
                if (table.name == "feed_categories") {
                    deleteFeedCategoryAssignments(db, restoredFeedIds)
                }

                val columnMetadata = table.loadColumnMetadata(db)
                var tableRows = 0
                reader.beginArray()
                UpsertStatements(db, table).use { statements ->
                    while (reader.hasNext()) {
                        require(totalRows < EasyPodBackupManifestCodec.MAX_BACKUP_ROWS) {
                            "Backup contains too many rows"
                        }
                        val row = readRow(reader, table, columnMetadata)
                        remapIdentity(table.name, row, identities, restoredFeedIds)
                        statements.upsert(row)
                        tableRows++
                        totalRows++
                    }
                }
                reader.endArray()
                reader.endObject()
                counts[table.name] = tableRows
            }

            require(!reader.hasNext()) { "Backup contains unsupported tables" }
            reader.endArray()
            require(!reader.hasNext()) { "Backup JSON contains unsupported fields" }
            reader.endObject()
        }

        require(totalRows == manifest.rowCount) {
            "Backup row count does not match its manifest"
        }
        return counts
    }

    private fun readRow(
        reader: JsonReader,
        table: TableSpec,
        metadata: Map<String, ColumnMetadata>,
    ): MutableMap<String, BackupValue> {
        val row = linkedMapOf<String, BackupValue>()
        reader.beginObject()
        while (reader.hasNext()) {
            val column = reader.nextName()
            require(column in table.columnSet && column !in row) {
                "Backup table ${table.name} contains an invalid column"
            }
            val value = reader.readBackupValue()
            metadata.getValue(column).validate(value, table.name, column)
            row[column] = value
        }
        reader.endObject()
        require(row.keys == table.columnSet) {
            "Backup table ${table.name} row is incomplete"
        }
        return row
    }

    private fun remapIdentity(
        tableName: String,
        row: MutableMap<String, BackupValue>,
        identities: RestoreIdentities,
        restoredFeedIds: MutableSet<String>,
    ) {
        when (tableName) {
            "feeds" -> {
                val backupId = row.requiredString("id")
                val feedUrl = row.stringOrNull("feedUrl")
                val restoredId = feedUrl
                    ?.let(identities.feedIdsByUrl::get)
                    ?: backupId
                identities.feedIdRemap[backupId] = restoredId
                feedUrl?.let { identities.feedIdsByUrl[it] = restoredId }
                row["id"] = BackupValue.Text(restoredId)
                restoredFeedIds += restoredId
            }

            "categories" -> {
                val backupId = row.requiredString("id")
                val name = row.requiredString("name")
                val restoredId = identities.categoryIdsByName[name] ?: backupId
                identities.categoryIdRemap[backupId] = restoredId
                identities.categoryIdsByName[name] = restoredId
                row["id"] = BackupValue.Text(restoredId)
            }

            "episodes" -> {
                row.remapNullable("feedId", identities.feedIdRemap)
                val backupId = row.requiredString("id")
                val feedId = row.stringOrNull("feedId")
                val mediaUrl = row.stringOrNull("mediaUrl")
                val key = backupEpisodeIdentityKey(feedId, mediaUrl)
                val restoredId = key
                    ?.let(identities.episodeIdsByFeedAndUrl::get)
                    ?: backupId
                identities.episodeIdRemap[backupId] = restoredId
                key?.let { identities.episodeIdsByFeedAndUrl[it] = restoredId }
                row["id"] = BackupValue.Text(restoredId)
            }

            "feed_categories" -> {
                row.remapRequired("feedId", identities.feedIdRemap)
                row.remapRequired("categoryId", identities.categoryIdRemap)
            }

            "smart_playlists" -> {
                row.remapNullable("legacyFeedId", identities.feedIdRemap)
                row.remapNullable("categoryIdRaw", identities.categoryIdRemap)
            }

            "queue_entries" -> {
                row.remapRequired("episodeId", identities.episodeIdRemap)
            }

            "playback_state" -> {
                row.remapNullable("currentEpisodeId", identities.episodeIdRemap)
                row["sleepTimerEndsAt"] = BackupValue.Null
                row["sleepAtEpisodeEnd"] = BackupValue.Integer(0)
            }
        }
    }

    private fun deleteFeedCategoryAssignments(
        db: SupportSQLiteDatabase,
        feedIds: Set<String>,
    ) {
        if (feedIds.isEmpty()) return
        db.compileStatement("DELETE FROM `feed_categories` WHERE `feedId` = ?").use {
            feedIds.forEach { feedId ->
                it.clearBindings()
                it.bindString(1, feedId)
                it.executeUpdateDelete()
            }
        }
    }

    private fun extractArchive(archive: File, directory: File): ExtractedBackup {
        var manifest: File? = null
        var data: File? = null
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "Backup contains too many entries" }
                require(
                    !entry.isDirectory &&
                        EasyPodBackupArchiveSafety.isSafeEntryName(entry.name),
                ) {
                    "Backup contains an unsafe entry"
                }
                val limit = when (entry.name) {
                    EasyPodBackupManifestCodec.MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                    EasyPodBackupManifestCodec.DATA_ENTRY -> MAX_DATA_BYTES
                    else -> throw IllegalArgumentException(
                        "Backup contains an unsupported entry",
                    )
                }
                val output = File(directory, entry.name)
                require(!output.exists()) { "Backup contains a duplicate entry" }
                val written = FileOutputStream(output).buffered().use { destination ->
                    EasyPodBackupArchiveSafety.copyLimited(zip, destination, limit)
                }
                totalBytes += written
                require(totalBytes <= MAX_EXTRACTED_BYTES) {
                    "Backup expands beyond the supported size"
                }
                when (entry.name) {
                    EasyPodBackupManifestCodec.MANIFEST_ENTRY -> manifest = output
                    EasyPodBackupManifestCodec.DATA_ENTRY -> data = output
                }
                zip.closeEntry()
            }
        }
        return ExtractedBackup(
            manifest = requireNotNull(manifest) { "Backup manifest is missing" },
            data = requireNotNull(data) { "Backup library data is missing" },
        )
    }

    private inline fun <T> withTemporaryDirectory(
        prefix: String,
        block: (File) -> T,
    ): T {
        val root = File(appContext.cacheDir, "easypod-backups").apply { mkdirs() }
        val directory = File(root, "$prefix-${System.nanoTime()}").apply { mkdirs() }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class ExtractedBackup(
        val manifest: File,
        val data: File,
    )

    private data class TableSpec(
        val name: String,
        val columns: List<String>,
        val primaryKeys: List<String>,
        val replaceBeforeRestore: Boolean = false,
    ) {
        val columnSet = columns.toSet()

        fun quotedColumns(): String = columns.joinToString(",") { "`$it`" }

        fun loadColumnMetadata(db: SupportSQLiteDatabase): Map<String, ColumnMetadata> {
            val result = mutableMapOf<String, ColumnMetadata>()
            db.query("PRAGMA table_info(`$name`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    val column = cursor.getString(nameIndex)
                    if (column in columnSet) {
                        result[column] = ColumnMetadata(
                            affinity = cursor.getString(typeIndex).uppercase(),
                            nullable = cursor.getInt(notNullIndex) == 0 &&
                                cursor.getInt(primaryKeyIndex) == 0,
                        )
                    }
                }
            }
            require(result.keys == columnSet) {
                "EasyPod database does not match the backup schema"
            }
            return result
        }
    }

    private data class ColumnMetadata(
        val affinity: String,
        val nullable: Boolean,
    ) {
        fun validate(value: BackupValue, table: String, column: String) {
            if (value == BackupValue.Null) {
                require(nullable) { "Backup has null for $table.$column" }
                return
            }
            val valid = when {
                affinity.contains("INT") ->
                    value is BackupValue.Integer || value is BackupValue.Boolean

                affinity.contains("REAL") ||
                    affinity.contains("FLOA") ||
                    affinity.contains("DOUB") ->
                    value is BackupValue.Integer || value is BackupValue.Real

                affinity.contains("CHAR") ||
                    affinity.contains("CLOB") ||
                    affinity.contains("TEXT") ->
                    value is BackupValue.Text

                else -> false
            }
            require(valid) { "Backup value type is invalid for $table.$column" }
        }
    }

    private class UpsertStatements(
        db: SupportSQLiteDatabase,
        private val table: TableSpec,
    ) : AutoCloseable {
        private val updateColumns = table.columns.filterNot(table.primaryKeys::contains)
        private val update = db.compileStatement(
            "UPDATE `${table.name}` SET " +
                updateColumns.joinToString(",") { "`$it` = ?" } +
                " WHERE " +
                table.primaryKeys.joinToString(" AND ") { "`$it` = ?" },
        )
        private val insert = db.compileStatement(
            "INSERT INTO `${table.name}` (${table.quotedColumns()}) VALUES (" +
                table.columns.joinToString(",") { "?" } +
                ")",
        )

        fun upsert(row: Map<String, BackupValue>) {
            update.clearBindings()
            var index = 1
            updateColumns.forEach { column ->
                update.bindBackupValue(index++, row.getValue(column))
            }
            table.primaryKeys.forEach { column ->
                update.bindBackupValue(index++, row.getValue(column))
            }
            if (update.executeUpdateDelete() > 0) return

            insert.clearBindings()
            table.columns.forEachIndexed { columnIndex, column ->
                insert.bindBackupValue(columnIndex + 1, row.getValue(column))
            }
            insert.executeInsert()
        }

        override fun close() {
            update.close()
            insert.close()
        }
    }

    private class RestoreIdentities(
        val feedIdsByUrl: MutableMap<String, String>,
        val categoryIdsByName: MutableMap<String, String>,
        val episodeIdsByFeedAndUrl: MutableMap<String, String>,
        val feedIdRemap: MutableMap<String, String> = mutableMapOf(),
        val categoryIdRemap: MutableMap<String, String> = mutableMapOf(),
        val episodeIdRemap: MutableMap<String, String> = mutableMapOf(),
    ) {
        companion object {
            fun load(db: SupportSQLiteDatabase): RestoreIdentities {
                val feeds = mutableMapOf<String, String>()
                db.query("SELECT `id`, `feedUrl` FROM `feeds` WHERE `feedUrl` IS NOT NULL")
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            feeds.getOrPut(cursor.getString(1)) {
                                cursor.getString(0)
                            }
                        }
                    }
                val categories = mutableMapOf<String, String>()
                db.query("SELECT `id`, `name` FROM `categories`").use { cursor ->
                    while (cursor.moveToNext()) {
                        categories.getOrPut(cursor.getString(1)) {
                            cursor.getString(0)
                        }
                    }
                }
                val episodes = mutableMapOf<String, String>()
                db.query(
                    """
                    SELECT `id`, `feedId`, `mediaUrl`
                    FROM `episodes`
                    WHERE `mediaUrl` IS NOT NULL
                    """.trimIndent(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        backupEpisodeIdentityKey(
                            cursor.getString(1),
                            cursor.getString(2),
                        )?.let { key ->
                            episodes.getOrPut(key) { cursor.getString(0) }
                        }
                    }
                }
                return RestoreIdentities(feeds, categories, episodes)
            }
        }
    }

    private fun Cursor.writeValue(writer: JsonWriter, index: Int) {
        when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> writer.nullValue()
            Cursor.FIELD_TYPE_INTEGER -> writer.value(getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> writer.value(getDouble(index))
            Cursor.FIELD_TYPE_STRING -> writer.value(getString(index))
            else -> throw IllegalArgumentException("Backup contains an unsupported value type")
        }
    }

    private fun JsonReader.readBackupValue(): BackupValue = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            BackupValue.Null
        }

        JsonToken.BOOLEAN -> BackupValue.Boolean(nextBoolean())
        JsonToken.STRING -> {
            val value = nextString()
            require(value.length <= MAX_STRING_CHARS) {
                "Backup contains an oversized text value"
            }
            BackupValue.Text(value)
        }

        JsonToken.NUMBER -> {
            val value = nextString()
            value.toLongOrNull()?.let(BackupValue::Integer)
                ?: value.toDoubleOrNull()
                    ?.takeIf(Double::isFinite)
                    ?.let(BackupValue::Real)
                ?: throw IllegalArgumentException("Backup contains an invalid number")
        }

        else -> throw IllegalArgumentException("Backup contains a nested JSON value")
    }

    private fun MutableMap<String, BackupValue>.requiredString(column: String): String =
        (getValue(column) as? BackupValue.Text)?.value
            ?: throw IllegalArgumentException("Backup identity $column is invalid")

    private fun Map<String, BackupValue>.stringOrNull(column: String): String? =
        when (val value = getValue(column)) {
            BackupValue.Null -> null
            is BackupValue.Text -> value.value
            else -> throw IllegalArgumentException("Backup identity $column is invalid")
        }

    private fun MutableMap<String, BackupValue>.remapRequired(
        column: String,
        remap: Map<String, String>,
    ) {
        val value = requiredString(column)
        remap[value]?.let { this[column] = BackupValue.Text(it) }
    }

    private fun MutableMap<String, BackupValue>.remapNullable(
        column: String,
        remap: Map<String, String>,
    ) {
        val value = stringOrNull(column) ?: return
        remap[value]?.let { this[column] = BackupValue.Text(it) }
    }

    private companion object {
        val DiscardingOutputStream = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }

        const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 16L * 1024L
        const val MAX_DATA_BYTES = 256L * 1024L * 1024L
        const val MAX_EXTRACTED_BYTES = MAX_DATA_BYTES + MAX_MANIFEST_BYTES
        const val MAX_ZIP_ENTRIES = 2
        const val MAX_STRING_CHARS = 2 * 1024 * 1024

        val TABLES = listOf(
            TableSpec(
                name = "feeds",
                columns = listOf(
                    "id",
                    "legacyRowId",
                    "legacyFeedId",
                    "title",
                    "feedUrl",
                    "imageUrl",
                    "categoryAssignmentRaw",
                    "autoDelete",
                    "viewMode",
                    "feedType",
                    "hasUnread",
                    "publishedAt",
                    "serverPublishedAt",
                    "customDownload",
                    "forceUniqueNames",
                    "truncateLongNames",
                    "forceItemSort",
                    "lastItemId",
                    "trackSort",
                    "feedPlayer",
                    "username",
                    "maxDownloads",
                    "maxSyncedItems",
                    "maxEpisodes",
                    "maxEpisodeAge",
                    "savePlayedPosition",
                    "fingerprintType",
                    "audioSettings",
                ),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "categories",
                columns = listOf("id", "name", "color"),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "episodes",
                columns = listOf(
                    "id",
                    "legacyRowId",
                    "legacyItemId",
                    "legacyRssItemId",
                    "legacyParentFeedId",
                    "feedId",
                    "title",
                    "mediaUrl",
                    "postUrl",
                    "mimeType",
                    "protocol",
                    "durationMs",
                    "positionMs",
                    "publishedAt",
                    "downloadSizeBytes",
                    "downloadPortionRaw",
                    "played",
                    "locked",
                    "description",
                    "showNotes",
                ),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "feed_categories",
                columns = listOf("feedId", "categoryId", "slot"),
                primaryKeys = listOf("feedId", "categoryId"),
            ),
            TableSpec(
                name = "scheduled_updates",
                columns = listOf(
                    "id",
                    "legacyRowId",
                    "legacyTaskId",
                    "active",
                    "minimumBatteryPercent",
                    "startTimeRaw",
                    "recurrencePeriodRaw",
                    "recurrenceIntervalRaw",
                    "operationIdRaw",
                    "stateRaw",
                ),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "smart_playlists",
                columns = listOf(
                    "id",
                    "legacyRowId",
                    "legacyPlaylistId",
                    "name",
                    "legacyFeedId",
                    "sortOrderRaw",
                    "categoryIdRaw",
                    "episodeCount",
                    "episodeFilterRaw",
                    "playbackTypeRaw",
                    "configRaw",
                ),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "episode_history",
                columns = listOf(
                    "id",
                    "legacyRowId",
                    "episodeUrl",
                    "feedUrl",
                    "timestamp",
                    "entryTypeRaw",
                ),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "queue_entries",
                columns = listOf("episodeId", "position", "addedAt"),
                primaryKeys = listOf("episodeId"),
                replaceBeforeRestore = true,
            ),
            TableSpec(
                name = "playback_state",
                columns = listOf(
                    "id",
                    "currentEpisodeId",
                    "positionMs",
                    "playbackSpeed",
                    "updatedAt",
                    "sleepTimerEndsAt",
                    "sleepAtEpisodeEnd",
                ),
                primaryKeys = listOf("id"),
                replaceBeforeRestore = true,
            ),
            TableSpec(
                name = "automation_settings",
                columns = listOf(
                    "id",
                    "refreshEnabled",
                    "refreshIntervalHours",
                    "wifiOnly",
                    "chargingOnly",
                    "autoDownloadEnabled",
                    "defaultMaxDownloads",
                    "lastRunAt",
                    "lastStatus",
                    "lastMessage",
                ),
                primaryKeys = listOf("id"),
            ),
            TableSpec(
                name = "player_settings",
                columns = listOf(
                    "id",
                    "forwardSkipSeconds",
                    "backwardSkipSeconds",
                    "loudnessBoostEnabled",
                    "pauseOnHeadsetDisconnect",
                    "defaultSleepMinutes",
                ),
                primaryKeys = listOf("id"),
            ),
        )
    }
}
