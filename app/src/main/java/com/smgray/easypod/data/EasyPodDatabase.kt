package com.smgray.easypod.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val EASYPOD_DATABASE_VERSION = 4

@Database(
    entities = [
        FeedEntity::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        FeedCategoryCrossRef::class,
        ScheduledUpdateEntity::class,
        SmartPlaylistEntity::class,
        EpisodeHistoryEntity::class,
        MigrationRunEntity::class,
        QueueEntryEntity::class,
        DownloadEntity::class,
        PlaybackStateEntity::class,
        AutomationSettingsEntity::class,
        PlayerSettingsEntity::class,
    ],
    version = EASYPOD_DATABASE_VERSION,
    exportSchema = true,
)
abstract class EasyPodDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun automationDao(): AutomationDao
    abstract fun smartPlayDao(): SmartPlayDao
    abstract fun playerSettingsDao(): PlayerSettingsDao
    abstract fun migrationDao(): MigrationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queue_entries` (
                        `episodeId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`episodeId`),
                        FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_queue_entries_position`
                    ON `queue_entries` (`position`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `episodeId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `localPath` TEXT,
                        `bytesDownloaded` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `requestedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `errorMessage` TEXT,
                        PRIMARY KEY(`episodeId`),
                        FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_state` (
                        `id` INTEGER NOT NULL,
                        `currentEpisodeId` TEXT,
                        `positionMs` INTEGER NOT NULL,
                        `playbackSpeed` REAL NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automation_settings` (
                        `id` INTEGER NOT NULL,
                        `refreshEnabled` INTEGER NOT NULL,
                        `refreshIntervalHours` INTEGER NOT NULL,
                        `wifiOnly` INTEGER NOT NULL,
                        `chargingOnly` INTEGER NOT NULL,
                        `autoDownloadEnabled` INTEGER NOT NULL,
                        `defaultMaxDownloads` INTEGER NOT NULL,
                        `lastRunAt` INTEGER,
                        `lastStatus` TEXT NOT NULL,
                        `lastMessage` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `automation_settings` (
                        `id`,
                        `refreshEnabled`,
                        `refreshIntervalHours`,
                        `wifiOnly`,
                        `chargingOnly`,
                        `autoDownloadEnabled`,
                        `defaultMaxDownloads`,
                        `lastRunAt`,
                        `lastStatus`,
                        `lastMessage`
                    )
                    SELECT
                        1,
                        CASE WHEN EXISTS (
                            SELECT 1 FROM `scheduled_updates` WHERE `active` = 1
                        ) THEN 1 ELSE 0 END,
                        12,
                        1,
                        0,
                        1,
                        3,
                        NULL,
                        'Never run',
                        NULL
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `playback_state` " +
                        "ADD COLUMN `sleepTimerEndsAt` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `playback_state` " +
                        "ADD COLUMN `sleepAtEpisodeEnd` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `player_settings` (
                        `id` INTEGER NOT NULL,
                        `forwardSkipSeconds` INTEGER NOT NULL,
                        `backwardSkipSeconds` INTEGER NOT NULL,
                        `loudnessBoostEnabled` INTEGER NOT NULL,
                        `pauseOnHeadsetDisconnect` INTEGER NOT NULL,
                        `defaultSleepMinutes` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `player_settings` (
                        `id`,
                        `forwardSkipSeconds`,
                        `backwardSkipSeconds`,
                        `loudnessBoostEnabled`,
                        `pauseOnHeadsetDisconnect`,
                        `defaultSleepMinutes`
                    ) VALUES (1, 30, 15, 0, 1, 30)
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): EasyPodDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EasyPodDatabase::class.java,
                "easypod.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
