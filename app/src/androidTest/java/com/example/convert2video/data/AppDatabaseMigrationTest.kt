package com.example.convert2video.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [AppDatabase.MIGRATION_2_3] adds nullable segment columns while preserving rows.
 * Uses a hand-built v2 schema (exportSchema=false) then runs the Migration object.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @Test
    fun success_migrate2to3_preservesRowAndAddsNullableSegmentColumns() {
        // Given — v2 conversion_records with one row
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "c2v_migration_2_3_test"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `conversion_records` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `audioUri` TEXT NOT NULL,
                                    `videoUri` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = openHelper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                    "VALUES ('content://audio/a', 'content://video/v', 12345)",
            )

            // When
            AppDatabase.MIGRATION_2_3.migrate(db)

            // Then — existing data kept; new columns present and NULL
            db.query("SELECT audioUri, videoUri, createdAt, segmentBatchId, segmentIndex, segmentTotal FROM conversion_records")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("content://audio/a", cursor.getString(0))
                    assertEquals("content://video/v", cursor.getString(1))
                    assertEquals(12345L, cursor.getLong(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                    assertFalse(cursor.moveToNext())
                }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun success_migrate4to5_preservesRowsAndCreatesRecordingRecords() {
        // Given — v4 schema with one row in each existing table
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "c2v_migration_4_5_test"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `backgrounds` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `filePath` TEXT NOT NULL,
                                    `addedAt` INTEGER NOT NULL,
                                    `isSelected` INTEGER NOT NULL DEFAULT 0
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `conversion_records` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `audioUri` TEXT NOT NULL,
                                    `videoUri` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `segmentBatchId` TEXT,
                                    `segmentIndex` INTEGER,
                                    `segmentTotal` INTEGER
                                )
                                """.trimIndent(),
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
                                """.trimIndent(),
                            )
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
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = openHelper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO `backgrounds` (`filePath`, `addedAt`, `isSelected`) " +
                    "VALUES ('/files/bg1.jpg', 1000, 1)",
            )
            db.execSQL(
                "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                    "VALUES ('content://audio/a', 'content://video/v', 12345)",
            )
            db.execSQL(
                "INSERT INTO `upload_records` (`videoUri`, `youtubeVideoId`, `watchUrl`, `uploadedAt`) " +
                    "VALUES ('content://video/v', 'yt123', 'https://youtu.be/yt123', 55555)",
            )
            db.execSQL(
                "INSERT INTO `error_log_entries` (`level`, `tag`, `message`, `createdAt`) " +
                    "VALUES ('E', 'Tag', 'msg', 777)",
            )

            // When
            AppDatabase.MIGRATION_4_5.migrate(db)

            // Then
            db.query("SELECT filePath FROM backgrounds").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("/files/bg1.jpg", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM recording_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun success_migrate5to6_preservesRowsAndCreatesRecordingSchedules() {
        // Given — v5 schema with one row in each existing table
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "c2v_migration_5_6_test"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `backgrounds` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `filePath` TEXT NOT NULL,
                                    `addedAt` INTEGER NOT NULL,
                                    `isSelected` INTEGER NOT NULL DEFAULT 0
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `conversion_records` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `audioUri` TEXT NOT NULL,
                                    `videoUri` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `segmentBatchId` TEXT,
                                    `segmentIndex` INTEGER,
                                    `segmentTotal` INTEGER
                                )
                                """.trimIndent(),
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
                                """.trimIndent(),
                            )
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
                                """.trimIndent(),
                            )
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

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = openHelper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO `backgrounds` (`filePath`, `addedAt`, `isSelected`) " +
                    "VALUES ('/files/bg1.jpg', 1000, 1)",
            )
            db.execSQL(
                "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                    "VALUES ('content://audio/a', 'content://video/v', 12345)",
            )
            db.execSQL(
                "INSERT INTO `upload_records` (`videoUri`, `youtubeVideoId`, `watchUrl`, `uploadedAt`) " +
                    "VALUES ('content://video/v', 'yt123', 'https://youtu.be/yt123', 55555)",
            )
            db.execSQL(
                "INSERT INTO `error_log_entries` (`level`, `tag`, `message`, `createdAt`) " +
                    "VALUES ('E', 'Tag', 'msg', 777)",
            )
            db.execSQL(
                "INSERT INTO `recording_records` " +
                    "(`filePath`, `format`, `durationMs`, `sizeBytes`, `createdAt`) " +
                    "VALUES ('/Music/C2V/rec.m4a', 'AAC', 1000, 2048, 999)",
            )

            // When
            AppDatabase.MIGRATION_5_6.migrate(db)

            // Then — all existing tables preserved; recording_schedules exists empty with expected columns
            db.query("SELECT filePath FROM backgrounds").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("/files/bg1.jpg", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT audioUri, videoUri, createdAt FROM conversion_records",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://audio/a", cursor.getString(0))
                assertEquals("content://video/v", cursor.getString(1))
                assertEquals(12345L, cursor.getLong(2))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT videoUri, youtubeVideoId, watchUrl, uploadedAt FROM upload_records",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://video/v", cursor.getString(0))
                assertEquals("yt123", cursor.getString(1))
                assertEquals("https://youtu.be/yt123", cursor.getString(2))
                assertEquals(55555L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT level, tag, message, createdAt FROM error_log_entries",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("E", cursor.getString(0))
                assertEquals("Tag", cursor.getString(1))
                assertEquals("msg", cursor.getString(2))
                assertEquals(777L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT filePath, format FROM recording_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("/Music/C2V/rec.m4a", cursor.getString(0))
                assertEquals("AAC", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM recording_schedules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // Then — migrate 후 INSERT 1행 + auto pk 확인
            db.execSQL(
                "INSERT INTO `recording_schedules` " +
                    "(`startMinuteOfDay`, `endMinuteOfDay`, `repeatMode`, `daysOfWeekMask`, `enabled`, `createdAt`) " +
                    "VALUES (480, 540, 'ONCE', 0, 1, 1000)",
            )
            db.query(
                "SELECT id, startMinuteOfDay, endMinuteOfDay, repeatMode, daysOfWeekMask, enabled, createdAt " +
                    "FROM recording_schedules",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("expected auto-generated pk > 0", cursor.getLong(0) > 0L)
                assertEquals(480, cursor.getInt(1))
                assertEquals(540, cursor.getInt(2))
                assertEquals("ONCE", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(1, cursor.getInt(5))
                assertEquals(1000L, cursor.getLong(6))
                assertFalse(cursor.moveToNext())
            }

            // Then — recording_schedules column schema (PRAGMA table_info)
            val expectedColumns = mapOf(
                "id" to "INTEGER",
                "startMinuteOfDay" to "INTEGER",
                "endMinuteOfDay" to "INTEGER",
                "repeatMode" to "TEXT",
                "daysOfWeekMask" to "INTEGER",
                "enabled" to "INTEGER",
                "createdAt" to "INTEGER",
            )
            db.query("PRAGMA table_info(`recording_schedules`)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notnullIdx = cursor.getColumnIndexOrThrow("notnull")
                val actual = linkedMapOf<String, Pair<String, Int>>()
                while (cursor.moveToNext()) {
                    actual[cursor.getString(nameIdx)] =
                        cursor.getString(typeIdx) to cursor.getInt(notnullIdx)
                }
                assertEquals(expectedColumns.keys, actual.keys)
                for ((col, expectedType) in expectedColumns) {
                    val (actualType, notNull) = actual.getValue(col)
                    assertEquals("column $col type", expectedType, actualType)
                    assertEquals("column $col NOT NULL", 1, notNull)
                }
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun success_migrate6to7_preservesRowsAndCreatesTrashedItems() {
        // Given — v6 schema with one row in each existing table
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "c2v_migration_6_7_test"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `backgrounds` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `filePath` TEXT NOT NULL,
                                    `addedAt` INTEGER NOT NULL,
                                    `isSelected` INTEGER NOT NULL DEFAULT 0
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `conversion_records` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `audioUri` TEXT NOT NULL,
                                    `videoUri` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `segmentBatchId` TEXT,
                                    `segmentIndex` INTEGER,
                                    `segmentTotal` INTEGER
                                )
                                """.trimIndent(),
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
                                """.trimIndent(),
                            )
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
                                """.trimIndent(),
                            )
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

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = openHelper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO `backgrounds` (`filePath`, `addedAt`, `isSelected`) " +
                    "VALUES ('/files/bg1.jpg', 1000, 1)",
            )
            db.execSQL(
                "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                    "VALUES ('content://audio/a', 'content://video/v', 12345)",
            )
            db.execSQL(
                "INSERT INTO `upload_records` (`videoUri`, `youtubeVideoId`, `watchUrl`, `uploadedAt`) " +
                    "VALUES ('content://video/v', 'yt123', 'https://youtu.be/yt123', 55555)",
            )
            db.execSQL(
                "INSERT INTO `error_log_entries` (`level`, `tag`, `message`, `createdAt`) " +
                    "VALUES ('E', 'Tag', 'msg', 777)",
            )
            db.execSQL(
                "INSERT INTO `recording_records` " +
                    "(`filePath`, `format`, `durationMs`, `sizeBytes`, `createdAt`) " +
                    "VALUES ('/Music/C2V/rec.m4a', 'AAC', 1000, 2048, 999)",
            )
            db.execSQL(
                "INSERT INTO `recording_schedules` " +
                    "(`startMinuteOfDay`, `endMinuteOfDay`, `repeatMode`, `daysOfWeekMask`, `enabled`, `createdAt`) " +
                    "VALUES (480, 540, 'ONCE', 0, 1, 1000)",
            )

            // When
            AppDatabase.MIGRATION_6_7.migrate(db)

            // Then — existing tables preserved; trashed_items exists empty
            db.query("SELECT filePath FROM backgrounds").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("/files/bg1.jpg", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT audioUri, videoUri, createdAt FROM conversion_records",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://audio/a", cursor.getString(0))
                assertEquals("content://video/v", cursor.getString(1))
                assertEquals(12345L, cursor.getLong(2))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT videoUri, youtubeVideoId, watchUrl, uploadedAt FROM upload_records",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://video/v", cursor.getString(0))
                assertEquals("yt123", cursor.getString(1))
                assertEquals("https://youtu.be/yt123", cursor.getString(2))
                assertEquals(55555L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT level, tag, message, createdAt FROM error_log_entries",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("E", cursor.getString(0))
                assertEquals("Tag", cursor.getString(1))
                assertEquals("msg", cursor.getString(2))
                assertEquals(777L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT filePath, format FROM recording_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("/Music/C2V/rec.m4a", cursor.getString(0))
                assertEquals("AAC", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT startMinuteOfDay, enabled FROM recording_schedules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(480, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM trashed_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // Then — migrate 후 INSERT 1행 + auto pk + nullable columns NULL
            db.execSQL(
                "INSERT INTO `trashed_items` " +
                    "(`itemType`, `displayName`, `trashFilePath`, `deletedAt`, `wasIndexed`) " +
                    "VALUES ('RECORDING_AUDIO', 'rec.m4a', '/files/trash/a.m4a', 2000, 1)",
            )
            db.query(
                "SELECT id, itemType, displayName, trashFilePath, deletedAt, wasIndexed, " +
                    "originalFilePath, durationMs, recordingFormat, sourceAudioUri, mimeType " +
                    "FROM trashed_items",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("expected auto-generated pk > 0", cursor.getLong(0) > 0L)
                assertEquals("RECORDING_AUDIO", cursor.getString(1))
                assertEquals("rec.m4a", cursor.getString(2))
                assertEquals("/files/trash/a.m4a", cursor.getString(3))
                assertEquals(2000L, cursor.getLong(4))
                assertEquals(1, cursor.getInt(5))
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.isNull(7))
                assertTrue(cursor.isNull(8))
                assertTrue(cursor.isNull(9))
                assertTrue(cursor.isNull(10))
                assertFalse(cursor.moveToNext())
            }

            // Then — trashed_items 11-column schema (PRAGMA table_info)
            val expectedColumns = mapOf(
                "id" to ("INTEGER" to 1),
                "itemType" to ("TEXT" to 1),
                "displayName" to ("TEXT" to 1),
                "trashFilePath" to ("TEXT" to 1),
                "deletedAt" to ("INTEGER" to 1),
                "wasIndexed" to ("INTEGER" to 1),
                "originalFilePath" to ("TEXT" to 0),
                "durationMs" to ("INTEGER" to 0),
                "recordingFormat" to ("TEXT" to 0),
                "sourceAudioUri" to ("TEXT" to 0),
                "mimeType" to ("TEXT" to 0),
            )
            db.query("PRAGMA table_info(`trashed_items`)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notnullIdx = cursor.getColumnIndexOrThrow("notnull")
                val actual = linkedMapOf<String, Pair<String, Int>>()
                while (cursor.moveToNext()) {
                    actual[cursor.getString(nameIdx)] =
                        cursor.getString(typeIdx) to cursor.getInt(notnullIdx)
                }
                assertEquals(expectedColumns.keys.toList(), actual.keys.toList())
                for ((col, expected) in expectedColumns) {
                    val (expectedType, expectedNotNull) = expected
                    val (actualType, notNull) = actual.getValue(col)
                    assertEquals("column $col type", expectedType, actualType)
                    assertEquals("column $col notnull", expectedNotNull, notNull)
                }
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun success_migrate7to8_preservesRowsAndCreatesImportedAudioRecords() {
        // Given — v7 schema with one row in trashed_items
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "c2v_migration_7_8_test"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
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

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = openHelper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO `trashed_items` " +
                    "(`itemType`, `displayName`, `trashFilePath`, `deletedAt`, `wasIndexed`) " +
                    "VALUES ('RECORDING_AUDIO', 'rec.m4a', '/files/trash/a.m4a', 2000, 1)",
            )

            // When
            AppDatabase.MIGRATION_7_8.migrate(db)

            // Then — trashed_items preserved; imported_audio_records empty
            db.query("SELECT itemType, displayName FROM trashed_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RECORDING_AUDIO", cursor.getString(0))
                assertEquals("rec.m4a", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM imported_audio_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // Then — migrate 후 INSERT 1행 + auto pk
            db.execSQL(
                "INSERT INTO `imported_audio_records` " +
                    "(`filePath`, `originalDisplayName`, `durationMs`, `sizeBytes`, `createdAt`) " +
                    "VALUES ('/Music/C2VImported/song.mp3', 'song.mp3', 60000, 4096, 3000)",
            )
            db.query(
                "SELECT id, filePath, originalDisplayName, durationMs, sizeBytes, createdAt " +
                    "FROM imported_audio_records",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("expected auto-generated pk > 0", cursor.getLong(0) > 0L)
                assertEquals("/Music/C2VImported/song.mp3", cursor.getString(1))
                assertEquals("song.mp3", cursor.getString(2))
                assertEquals(60_000L, cursor.getLong(3))
                assertEquals(4096L, cursor.getLong(4))
                assertEquals(3000L, cursor.getLong(5))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun success_migrate8to9_backfillsRecordingBackupIdAndKeepsLegacyTrashUnknown() {
        // Given — v8 recording rows and a trash row created before stable backup IDs existed
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "c2v_migration_8_9_test"
        context.deleteDatabase(dbName)
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE `recording_records` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `filePath` TEXT NOT NULL,
                                    `format` TEXT NOT NULL,
                                    `durationMs` INTEGER NOT NULL,
                                    `sizeBytes` INTEGER NOT NULL,
                                    `createdAt` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE `trashed_items` (
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

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = openHelper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO recording_records " +
                    "(id, filePath, format, durationMs, sizeBytes, createdAt) " +
                    "VALUES (42, '/files/one.m4a', 'AAC', 1000, 2000, 3000)",
            )
            db.execSQL(
                "INSERT INTO trashed_items " +
                    "(itemType, displayName, trashFilePath, deletedAt, wasIndexed, recordingFormat) " +
                    "VALUES ('RECORDING_AUDIO', 'one.m4a', '/files/trash/one.m4a', 4000, 1, 'AAC')",
            )

            // When
            AppDatabase.MIGRATION_8_9.migrate(db)

            // Then — old active rows use their existing local ID; old trash has no fabricated ID
            db.query("SELECT id, backupId FROM recording_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42L, cursor.getLong(0))
                assertEquals(42L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT recordingBackupId FROM trashed_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
