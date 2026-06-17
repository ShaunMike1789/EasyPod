package com.smgray.easypod.migration

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.smgray.easypod.data.CategoryEntity
import com.smgray.easypod.data.EpisodeEntity
import com.smgray.easypod.data.EpisodeHistoryEntity
import com.smgray.easypod.data.FeedCategoryCrossRef
import com.smgray.easypod.data.FeedEntity
import com.smgray.easypod.data.MigrationRunEntity
import com.smgray.easypod.data.EasyPodDatabase
import com.smgray.easypod.data.ScheduledUpdateEntity
import com.smgray.easypod.data.SmartPlaylistEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LegacyImportResult(
    val feeds: Int,
    val episodes: Int,
    val categories: Int,
    val schedules: Int,
    val smartPlaylists: Int,
)

class LegacyBackupImporter(
    private val context: Context,
    private val easyPodDatabase: EasyPodDatabase,
) {
    suspend fun import(uri: Uri): LegacyImportResult = withContext(Dispatchers.IO) {
        val runId = UUID.randomUUID().toString()
        val sourceName = displayName(uri) ?: "Legacy podcast backup"
        val startedAt = System.currentTimeMillis()

        easyPodDatabase.migrationDao().putRun(
            MigrationRunEntity(
                id = runId,
                sourceName = sourceName,
                sourceUri = uri.toString(),
                startedAt = startedAt,
                completedAt = null,
                status = "RUNNING",
                feedCount = 0,
                episodeCount = 0,
                categoryCount = 0,
                message = null,
            ),
        )

        try {
            val runDirectory = File(context.cacheDir, "legacy-import/$runId")
            require(runDirectory.mkdirs()) { "Unable to create migration workspace" }
            val sourceFile = File(runDirectory, "source")
            copyContent(uri, sourceFile)

            val databaseFile = when {
                sourceFile.hasZipHeader() -> extractBackup(sourceFile, runDirectory)
                sourceFile.hasSqliteHeader() -> sourceFile
                else -> throw IllegalArgumentException(
                    "Select a legacy .bpbak archive or SQLite database file",
                )
            }

            val batch = readLegacyDatabase(databaseFile)
            easyPodDatabase.withTransaction {
                val libraryDao = easyPodDatabase.libraryDao()
                libraryDao.putFeeds(batch.feeds)
                libraryDao.putCategories(batch.categories)
                libraryDao.putFeedCategories(batch.feedCategories)
                libraryDao.putEpisodes(batch.episodes)
                libraryDao.putSchedules(batch.schedules)
                libraryDao.putSmartPlaylists(batch.smartPlaylists)
                libraryDao.putEpisodeHistory(batch.history)
            }

            val result = LegacyImportResult(
                feeds = batch.feeds.size,
                episodes = batch.episodes.size,
                categories = batch.categories.size,
                schedules = batch.schedules.size,
                smartPlaylists = batch.smartPlaylists.size,
            )
            easyPodDatabase.migrationDao().putRun(
                MigrationRunEntity(
                    id = runId,
                    sourceName = sourceName,
                    sourceUri = uri.toString(),
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    status = "COMPLETE",
                    feedCount = result.feeds,
                    episodeCount = result.episodes,
                    categoryCount = result.categories,
                    message = null,
                ),
            )
            runDirectory.deleteRecursively()
            result
        } catch (error: Exception) {
            easyPodDatabase.migrationDao().putRun(
                MigrationRunEntity(
                    id = runId,
                    sourceName = sourceName,
                    sourceUri = uri.toString(),
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    status = "FAILED",
                    feedCount = 0,
                    episodeCount = 0,
                    categoryCount = 0,
                    message = error.message ?: error::class.java.simpleName,
                ),
            )
            throw error
        }
    }

    private fun copyContent(uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected file")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                source.copyToWithLimit(output, MAX_SOURCE_SIZE)
            }
        }
    }

    private fun extractBackup(source: File, directory: File): File {
        val extracted = linkedMapOf<String, File>()
        ZipFile(source).use { archive ->
            val entries = archive.entries()
            var count = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                count++
                require(count <= MAX_ENTRY_COUNT) { "Backup contains too many entries" }

                val name = entry.name
                validateRootEntryName(name)
                require(entry.size == -1L || entry.size in 0..MAX_ENTRY_SIZE) {
                    "Backup entry $name is too large"
                }

                val destination = File(directory, name)
                require(destination.canonicalFile.parentFile == directory.canonicalFile) {
                    "Backup contains an unsafe path"
                }
                val bytes = archive.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyToWithLimit(output, MAX_ENTRY_SIZE)
                    }
                }
                totalBytes += bytes
                require(totalBytes <= MAX_SOURCE_SIZE) { "Expanded backup is too large" }
                extracted[name.lowercase()] = destination
            }
        }

        val manifestFile = extracted["backupmanifest.txt"]
            ?: throw IllegalArgumentException("BackupManifest.txt is missing")
        val manifest = LegacyBackupManifestParser.parse(manifestFile.readText())
        require(
            manifest.totalSize == null ||
                manifest.totalSize == manifest.files.sumOf(LegacyManifestFile::size),
        ) {
            "Backup total size does not match its manifest"
        }
        manifest.files.forEach { expected ->
            val actual = extracted[expected.name.lowercase()]
                ?: throw IllegalArgumentException("Backup entry ${expected.name} is missing")
            require(actual.length() == expected.size) {
                "Backup entry ${expected.name} does not match its manifest"
            }
        }

        return manifest.files
            .asSequence()
            .mapNotNull { expected -> extracted[expected.name.lowercase()] }
            .firstOrNull { it.hasSqliteHeader() }
            ?: throw IllegalArgumentException("The backup contains no supported podcast database")
    }

    @Suppress("DEPRECATION")
    private fun readLegacyDatabase(file: File): LegacyBatch {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            require(database.version <= MAX_LEGACY_DATABASE_VERSION) {
                "This legacy database version is newer than EasyPod supports"
            }
            require(database.tableExists("feeds") && database.tableExists("tracks")) {
                "The selected SQLite file is not a supported legacy podcast database"
            }

            val feeds = readFeeds(database)
            val feedIds = feeds.associateBy(
                keySelector = { it.legacyFeedId.orEmpty() },
                valueTransform = FeedEntity::id,
            )
            val categoryCatalog = readCategoryCatalog(database).toMutableList()
            feeds.flatMap { LegacyCategoryCodec.parseAssignments(it.categoryAssignmentRaw) }
                .forEach { assignedName ->
                    if (categoryCatalog.none { it.name.equals(assignedName, ignoreCase = true) }) {
                        categoryCatalog += LegacyCategory(assignedName, 0)
                    }
                }
            val categories = categoryCatalog.map {
                CategoryEntity(
                    id = stableId("legacy-category", it.name.lowercase()),
                    name = it.name,
                    color = it.color,
                )
            }
            val categoriesByName = categories.associateBy { it.name.lowercase() }
            val feedCategories = feeds.flatMap { feed ->
                LegacyCategoryCodec.parseAssignments(feed.categoryAssignmentRaw)
                    .mapIndexedNotNull { slot, name ->
                        categoriesByName[name.lowercase()]?.let { category ->
                            FeedCategoryCrossRef(feed.id, category.id, slot)
                        }
                    }
            }

            return LegacyBatch(
                feeds = feeds,
                episodes = readEpisodes(database, feedIds),
                categories = categories,
                feedCategories = feedCategories,
                schedules = readSchedules(database),
                smartPlaylists = readSmartPlaylists(database),
                history = readHistory(database),
            )
        } finally {
            database.close()
        }
    }

    private fun readFeeds(database: SQLiteDatabase): List<FeedEntity> =
        database.rawQuery("SELECT * FROM feeds", null).useRows { cursor ->
            val rowId = cursor.longOrNull("_id")
            val legacyFeedId = cursor.stringOrNull("feedId")
            FeedEntity(
                id = stableId("legacy-feed", legacyFeedId ?: rowId.toString()),
                legacyRowId = rowId,
                legacyFeedId = legacyFeedId,
                title = cursor.stringOrNull("name").orEmpty().ifBlank { "Untitled feed" },
                feedUrl = cursor.stringOrNull("url"),
                imageUrl = cursor.stringOrNull("imageUrl"),
                localPath = cursor.stringOrNull("path"),
                categoryAssignmentRaw = cursor.stringOrNull("category"),
                autoDelete = cursor.boolean("autodelete"),
                viewMode = cursor.int("view"),
                feedType = cursor.int("type"),
                hasUnread = cursor.boolean("hasUnread"),
                publishedAt = cursor.longOrNull("pubDate"),
                serverPublishedAt = cursor.longOrNull("srvPubDate"),
                customDownload = cursor.int("custDownload"),
                forceUniqueNames = cursor.boolean("forceUniqueNames"),
                truncateLongNames = cursor.boolean("leftTruncateLongTrackNames"),
                forceItemSort = cursor.boolean("forceItemSort"),
                lastItemId = cursor.stringOrNull("lastItemId"),
                trackSort = cursor.int("trackSort"),
                feedPlayer = cursor.int("feedPlayer"),
                username = cursor.stringOrNull("username"),
                maxDownloads = cursor.int("maxDownload"),
                maxSyncedItems = cursor.int("maxGReaderItems"),
                maxEpisodes = cursor.int("maxTracks"),
                maxEpisodeAge = cursor.int("maxTrackAge"),
                savePlayedPosition = cursor.boolean("savePlayedPosition"),
                fingerprintType = cursor.int("fingerprintType"),
                audioSettings = cursor.stringOrNull("audioSettings"),
            )
        }

    private fun readEpisodes(
        database: SQLiteDatabase,
        feedIds: Map<String, String>,
    ): List<EpisodeEntity> =
        database.rawQuery("SELECT * FROM tracks", null).useRows { cursor ->
            val rowId = cursor.longOrNull("_id")
            val parentFeedId = cursor.stringOrNull("parentFeedID")
            val mediaUrl = cursor.stringOrNull("url")
            val itemId = cursor.stringOrNull("orgFeedItemID")
            val rssItemId = cursor.stringOrNull("orgRssItemID")
            val identity = listOf(parentFeedId, itemId, rssItemId, mediaUrl, rowId)
                .joinToString("|")
            EpisodeEntity(
                id = stableId("legacy-episode", identity),
                legacyRowId = rowId,
                legacyItemId = itemId,
                legacyRssItemId = rssItemId,
                legacyParentFeedId = parentFeedId,
                feedId = feedIds[parentFeedId.orEmpty()],
                title = cursor.stringOrNull("name")
                    ?: cursor.stringOrNull("postTitle")
                    ?: "Untitled episode",
                mediaUrl = mediaUrl,
                postUrl = cursor.stringOrNull("postUrl"),
                localPath = cursor.stringOrNull("path"),
                imagePath = cursor.stringOrNull("imagePath"),
                mimeType = cursor.stringOrNull("contentType"),
                protocol = cursor.stringOrNull("protocol"),
                durationMs = cursor.long("totalTime"),
                positionMs = cursor.long("playedTime"),
                publishedAt = cursor.longOrNull("pubDate"),
                downloadSizeBytes = cursor.long("downloadSize"),
                downloadPortionRaw = cursor.long("downloadPortion"),
                played = cursor.boolean("played"),
                locked = cursor.boolean("locked"),
                description = cursor.stringOrNull("description"),
                showNotes = cursor.stringOrNull("showNotes"),
            )
        }

    private fun readCategoryCatalog(database: SQLiteDatabase): List<LegacyCategory> {
        if (!database.tableExists("categories")) return emptyList()
        return database.rawQuery("SELECT categories FROM categories", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    addAll(LegacyCategoryCodec.parseCatalog(cursor.stringOrNull("categories")))
                }
            }.distinctBy { it.name.lowercase() }
        }
    }

    private fun readSchedules(database: SQLiteDatabase): List<ScheduledUpdateEntity> {
        if (!database.tableExists("scheduled_tasks")) return emptyList()
        return database.rawQuery("SELECT * FROM scheduled_tasks", null).useRows { cursor ->
            val rowId = cursor.longOrNull("_id")
            val taskId = cursor.stringOrNull("taskId")
            ScheduledUpdateEntity(
                id = stableId("legacy-schedule", taskId ?: rowId.toString()),
                legacyRowId = rowId,
                legacyTaskId = taskId,
                active = cursor.boolean("active"),
                minimumBatteryPercent = cursor.int("minBattLevel"),
                startTimeRaw = cursor.long("startTime"),
                recurrencePeriodRaw = cursor.int("recPeriod"),
                recurrenceIntervalRaw = cursor.int("recInterval"),
                operationIdRaw = cursor.stringOrNull("operationId"),
                stateRaw = cursor.stringOrNull("state"),
            )
        }
    }

    private fun readSmartPlaylists(database: SQLiteDatabase): List<SmartPlaylistEntity> {
        if (!database.tableExists("smartplaylist")) return emptyList()
        val playlistNames = mutableMapOf<Long, String>()
        return database.rawQuery(
            "SELECT * FROM smartplaylist ORDER BY playlistId, sortOrder",
            null,
        ).useRows { cursor ->
            val rowId = cursor.longOrNull("_id")
            val playlistId = cursor.longOrNull("playlistId")
            val rawName = cursor.stringOrNull("playlistName")
            val name = rawName?.takeIf(String::isNotBlank)
                ?.also { playlistId?.let { id -> playlistNames[id] = it } }
                ?: playlistId?.let(playlistNames::get)
                ?: "SmartPlay"
            SmartPlaylistEntity(
                id = stableId(
                    "legacy-smart-playlist-rule",
                    listOf(
                        rowId,
                        playlistId,
                        cursor.int("sortOrder"),
                        cursor.stringOrNull("feedId"),
                        cursor.stringOrNull("categoryId"),
                    ).joinToString("|"),
                ),
                legacyRowId = rowId,
                legacyPlaylistId = playlistId,
                name = name,
                legacyFeedId = cursor.stringOrNull("feedId"),
                sortOrderRaw = cursor.int("sortOrder"),
                categoryIdRaw = cursor.stringOrNull("categoryId"),
                episodeCount = cursor.int("numEpisodes"),
                episodeFilterRaw = cursor.int("episodeFilter"),
                playbackTypeRaw = cursor.int("playbackType"),
                configRaw = cursor.stringOrNull("config"),
            )
        }
    }

    private fun readHistory(database: SQLiteDatabase): List<EpisodeHistoryEntity> {
        if (!database.tableExists("episode_history")) return emptyList()
        return database.rawQuery("SELECT * FROM episode_history", null).useRows { cursor ->
            val rowId = cursor.longOrNull("_id")
            val episodeUrl = cursor.stringOrNull("episodeUrl")
            val timestamp = cursor.long("timestamp")
            EpisodeHistoryEntity(
                id = stableId(
                    "legacy-history",
                    listOf(episodeUrl, cursor.stringOrNull("feedUrl"), timestamp, rowId)
                        .joinToString("|"),
                ),
                legacyRowId = rowId,
                episodeUrl = episodeUrl,
                feedUrl = cursor.stringOrNull("feedUrl"),
                timestamp = timestamp,
                entryTypeRaw = cursor.int("entryType"),
            )
        }
    }

    private fun SQLiteDatabase.tableExists(name: String): Boolean =
        rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(name),
        ).use(Cursor::moveToFirst)

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.stringOrNull(OpenableColumns.DISPLAY_NAME) else null
        }

    private fun validateRootEntryName(name: String) {
        require(
            name.isNotBlank() &&
                !name.contains("/") &&
                !name.contains("\\") &&
                name != "." &&
                name != "..",
        ) { "Backup contains an unsafe entry name" }
    }

    private fun stableId(namespace: String, value: String): String =
        UUID.nameUUIDFromBytes(
            "$namespace\u0000$value".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    private fun File.hasZipHeader(): Boolean =
        inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == header.size &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte()
        }

    private fun File.hasSqliteHeader(): Boolean =
        inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == header.size &&
                header.toString(StandardCharsets.US_ASCII) == "SQLite format 3\u0000"
        }

    private fun java.io.InputStream.copyToWithLimit(
        output: java.io.OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return total
            total += read
            require(total <= limit) { "Selected file is too large" }
            output.write(buffer, 0, read)
        }
    }

    private fun <T> Cursor.useRows(transform: (Cursor) -> T): List<T> = use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(transform(cursor))
        }
    }

    private fun Cursor.columnIndex(name: String): Int = getColumnIndex(name)

    private fun Cursor.stringOrNull(name: String): String? {
        val index = columnIndex(name)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(name: String): Long? {
        val index = columnIndex(name)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun Cursor.long(name: String): Long = longOrNull(name) ?: 0L

    private fun Cursor.int(name: String): Int = long(name).toInt()

    private fun Cursor.boolean(name: String): Boolean = long(name) != 0L

    private data class LegacyBatch(
        val feeds: List<FeedEntity>,
        val episodes: List<EpisodeEntity>,
        val categories: List<CategoryEntity>,
        val feedCategories: List<FeedCategoryCrossRef>,
        val schedules: List<ScheduledUpdateEntity>,
        val smartPlaylists: List<SmartPlaylistEntity>,
        val history: List<EpisodeHistoryEntity>,
    )

    private companion object {
        const val MAX_LEGACY_DATABASE_VERSION = 10
        const val MAX_ENTRY_COUNT = 64
        const val MAX_ENTRY_SIZE = 256L * 1024L * 1024L
        const val MAX_SOURCE_SIZE = 512L * 1024L * 1024L
    }
}
