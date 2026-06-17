package com.smgray.easypod.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smgray.easypod.data.EasyPodDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyMigrationInstrumentedTest {
    @Test
    fun importsSyntheticLegacyDatabaseWithoutUsingInstalledLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "legacy-migration-fixture.db")
        source.delete()
        createLegacyDatabase(source)
        val database = Room.inMemoryDatabaseBuilder(
            context,
            EasyPodDatabase::class.java,
        ).build()

        try {
            val result = LegacyBackupImporter(context, database)
                .import(Uri.fromFile(source))

            assertEquals(1, result.feeds)
            assertEquals(1, result.episodes)
            assertEquals(2, result.categories)
            assertEquals(1, result.schedules)
            assertEquals(2, result.smartPlaylists)

            val feed = requireNotNull(
                database.libraryDao().findFeedByUrl(
                    "https://example.com/feed.xml",
                ),
            )
            assertEquals("Legacy Test Feed", feed.title)
            assertEquals(1, feed.customDownload)
            assertEquals(2, database.libraryDao().getFeedCategories(feed.id).size)

            val episode = database.libraryDao().getEpisodesForFeed(feed.id).single()
            assertEquals("Legacy Episode", episode.title)
            assertEquals(61_000L, episode.positionMs)
            assertTrue(episode.played)
            assertTrue(episode.locked)

            val rules = database.smartPlayDao().getRules(7)
            assertEquals(listOf(0, 1), rules.map { it.position })
            assertEquals(listOf(3, 5), rules.map { it.episodeCount })
            assertEquals(listOf("Fixture SmartPlay", "Fixture SmartPlay"), rules.map { it.name })

            assertEquals(1, tableCount(database, "scheduled_updates"))
            assertEquals(1, tableCount(database, "episode_history"))
            assertEquals("COMPLETE", latestMigrationStatus(database))
        } finally {
            database.close()
            source.delete()
        }
    }

    private fun createLegacyDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.version = 10
            database.execSQL(
                """
                CREATE TABLE feeds (
                    _id INTEGER PRIMARY KEY,
                    feedId TEXT,
                    name TEXT,
                    url TEXT,
                    category TEXT,
                    custDownload INTEGER,
                    maxDownload INTEGER,
                    savePlayedPosition INTEGER
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO feeds
                    (_id, feedId, name, url, category, custDownload, maxDownload,
                     savePlayedPosition)
                VALUES
                    (1, 'legacy-feed-1', 'Legacy Test Feed',
                     'https://example.com/feed.xml', 'News|Daily', 1, 4, 1)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE tracks (
                    _id INTEGER PRIMARY KEY,
                    parentFeedID TEXT,
                    orgFeedItemID TEXT,
                    orgRssItemID TEXT,
                    url TEXT,
                    name TEXT,
                    contentType TEXT,
                    totalTime INTEGER,
                    playedTime INTEGER,
                    pubDate INTEGER,
                    played INTEGER,
                    locked INTEGER,
                    showNotes TEXT
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO tracks
                    (_id, parentFeedID, orgFeedItemID, orgRssItemID, url, name,
                     contentType, totalTime, playedTime, pubDate, played, locked,
                     showNotes)
                VALUES
                    (11, 'legacy-feed-1', 'item-11', 'rss-11',
                     'https://example.com/episode.mp3', 'Legacy Episode',
                     'audio/mpeg', 180000, 61000, 1718366400000, 1, 1,
                     'Migrated show notes')
                """.trimIndent(),
            )
            database.execSQL("CREATE TABLE categories (categories TEXT)")
            database.execSQL(
                "INSERT INTO categories VALUES ('News^-16776961|Daily^42')",
            )
            database.execSQL(
                """
                CREATE TABLE scheduled_tasks (
                    _id INTEGER PRIMARY KEY,
                    taskId TEXT,
                    active INTEGER,
                    minBattLevel INTEGER,
                    startTime INTEGER,
                    recPeriod INTEGER,
                    recInterval INTEGER,
                    operationId TEXT,
                    state TEXT
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO scheduled_tasks VALUES
                    (21, 'refresh-1', 1, 25, 600, 2, 12, 'update', 'ready')
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE smartplaylist (
                    _id INTEGER PRIMARY KEY,
                    playlistId INTEGER,
                    playlistName TEXT,
                    sortOrder INTEGER,
                    feedId TEXT,
                    categoryId TEXT,
                    numEpisodes INTEGER,
                    episodeFilter INTEGER,
                    playbackType INTEGER,
                    config TEXT
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO smartplaylist VALUES
                    (31, 7, 'Fixture SmartPlay', 0, 'legacy-feed-1', NULL,
                     3, -2, 3, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO smartplaylist VALUES
                    (32, 7, NULL, 1, NULL, 'News', 5, 1, 2, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE episode_history (
                    _id INTEGER PRIMARY KEY,
                    episodeUrl TEXT,
                    feedUrl TEXT,
                    timestamp INTEGER,
                    entryType INTEGER
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO episode_history VALUES
                    (41, 'https://example.com/episode.mp3',
                     'https://example.com/feed.xml', 1718366500000, 1)
                """.trimIndent(),
            )
        }
    }

    private fun tableCount(database: EasyPodDatabase, table: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM `$table`")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun latestMigrationStatus(database: EasyPodDatabase): String? =
        database.openHelper.readableDatabase
            .query("SELECT status FROM migration_runs ORDER BY startedAt DESC LIMIT 1")
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
}
