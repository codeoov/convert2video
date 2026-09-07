package com.example.convert2video.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BackgroundImage::class,
        ConversionRecord::class,
        UploadRecord::class,
        ErrorLogEntry::class,
        RecordingRecord::class,
        RecordingSchedule::class,
        TrashedItem::class,
        ImportedAudioRecord::class,
    ],
    version = 9,
    // TODO(2026-07-28): exportSchema=true + schema snapshot for migration regression CI
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun backgroundDao(): BackgroundDao
    abstract fun conversionRecordDao(): ConversionRecordDao
    abstract fun uploadRecordDao(): UploadRecordDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun recordingDao(): RecordingDao
    abstract fun recordingScheduleDao(): RecordingScheduleDao
    abstract fun trashedItemDao(): TrashedItemDao
    abstract fun importedAudioDao(): ImportedAudioDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversion_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `audioUri` TEXT NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `upload_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `youtubeVideoId` TEXT NOT NULL,
                        `watchUrl` TEXT NOT NULL,
                        `uploadedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversion_records` ADD COLUMN `segmentBatchId` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `conversion_records` ADD COLUMN `segmentIndex` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `conversion_records` ADD COLUMN `segmentTotal` INTEGER",
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `error_log_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `level` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `stackTrace` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_schedules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startMinuteOfDay` INTEGER NOT NULL,
                        `endMinuteOfDay` INTEGER NOT NULL,
                        `repeatMode` TEXT NOT NULL,
                        `daysOfWeekMask` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trashed_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `trashFilePath` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        `wasIndexed` INTEGER NOT NULL,
                        `originalFilePath` TEXT,
                        `durationMs` INTEGER,
                        `recordingFormat` TEXT,
                        `sourceAudioUri` TEXT,
                        `mimeType` TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_audio_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `originalDisplayName` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the stable recording backup identifier. Existing recording rows use their
         * already-stable local primary key; pre-v9 trash rows intentionally remain unknown.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `recording_records` ADD COLUMN `backupId` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE `recording_records` SET `backupId` = `id` WHERE `backupId` = 0",
                )
                db.execSQL(
                    "ALTER TABLE `trashed_items` ADD COLUMN `recordingBackupId` INTEGER",
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "convert2video.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                    )
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun clearInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
