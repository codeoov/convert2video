package com.example.convert2video.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.DriverManager

/**
 * JVM checks for [AppDatabase.MIGRATION_2_3]:
 * 1) SQL sniff via Proxy
 * 2) Real SQLite (sqlite-jdbc) row preservation + nullable segment columns
 *
 * Companion instrumented test: [AppDatabaseMigrationTest].
 */
class AppDatabaseMigrationJvmTest {

    @Test
    fun success_migration2_3_versionsAndAlterSql() {
        // Given
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    require(args != null && args.isNotEmpty())
                    executed += args[0] as String
                    null
                }
                else -> defaultProxyReturn(method.returnType)
            }
        } as SupportSQLiteDatabase

        // When
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
        AppDatabase.MIGRATION_2_3.migrate(db)

        // Then — three nullable column ADDs
        assertEquals(3, executed.size)
        assertTrue(executed[0].contains("segmentBatchId"))
        assertTrue(executed[0].contains("TEXT"))
        assertTrue(executed[1].contains("segmentIndex"))
        assertTrue(executed[1].contains("INTEGER"))
        assertTrue(executed[2].contains("segmentTotal"))
        assertTrue(executed[2].contains("INTEGER"))
        executed.forEach { sql ->
            assertTrue(sql.contains("ALTER TABLE") && sql.contains("conversion_records"))
        }
    }

    @Test
    fun success_migration2_3_preservesRowOnRealSqlite() {
        // Given — real in-memory SQLite with a v2 row
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE `conversion_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `audioUri` TEXT NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                        "VALUES ('content://audio/a', 'content://video/v', 12345)",
                )
            }

            val supportDb = jdbcSupportSqlite(conn)
            // When — run the real Migration object
            AppDatabase.MIGRATION_2_3.migrate(supportDb)

            // Then — existing row kept; new segment columns present and NULL
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT audioUri, videoUri, createdAt, segmentBatchId, segmentIndex, segmentTotal " +
                        "FROM conversion_records",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("content://audio/a", rs.getString(1))
                    assertEquals("content://video/v", rs.getString(2))
                    assertEquals(12345L, rs.getLong(3))
                    assertNull(rs.getObject(4))
                    assertNull(rs.getObject(5))
                    assertNull(rs.getObject(6))
                    assertTrue(!rs.next())
                }
            }
        }
    }

    @Test
    fun success_migration3_4_versionsAndCreateSql() {
        // Given
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    require(args != null && args.isNotEmpty())
                    executed += args[0] as String
                    null
                }
                else -> defaultProxyReturn(method.returnType)
            }
        } as SupportSQLiteDatabase

        // When
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)
        AppDatabase.MIGRATION_3_4.migrate(db)

        // Then — one CREATE TABLE IF NOT EXISTS for error_log_entries
        assertEquals(1, executed.size)
        val sql = executed[0]

        // 정확한 DDL 패턴 검증 (substring 피상 매칭 방지)
        assertTrue("CREATE TABLE IF NOT EXISTS 구문 누락", sql.contains("CREATE TABLE IF NOT EXISTS"))
        assertTrue("테이블명 error_log_entries 누락", sql.contains("error_log_entries"))
        // id: INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
        assertTrue("id 컬럼 INTEGER PRIMARY KEY AUTOINCREMENT 누락",
            sql.contains("INTEGER PRIMARY KEY AUTOINCREMENT"))
        // level, tag, message: NOT NULL
        assertTrue("level 컬럼 누락", sql.contains("`level`"))
        assertTrue("tag 컬럼 누락", sql.contains("`tag`"))
        assertTrue("message 컬럼 누락", sql.contains("`message`"))
        // stackTrace: nullable — NOT NULL 이 없어야 함 (stackTrace 뒤에 NOT NULL 없음)
        assertTrue("stackTrace 컬럼 누락", sql.contains("`stackTrace`"))
        val stackTraceIdx = sql.indexOf("`stackTrace`")
        val afterStackTrace = sql.substring(stackTraceIdx, minOf(stackTraceIdx + 30, sql.length))
        assertTrue("stackTrace는 nullable이어야 함(NOT NULL 없어야 함)",
            !afterStackTrace.contains("NOT NULL"))
        // createdAt: NOT NULL
        assertTrue("createdAt 컬럼 NOT NULL 누락",
            sql.contains("`createdAt` INTEGER NOT NULL"))
    }

    @Test
    fun success_migration3_4_preservesExistingRowsOnRealSqlite() {
        // Given — real in-memory SQLite with v3 schema rows in all three pre-existing tables
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                // backgrounds (v1 schema)
                st.execute(
                    """
                    CREATE TABLE `backgrounds` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        `isSelected` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO `backgrounds` (`filePath`, `addedAt`, `isSelected`) " +
                        "VALUES ('/files/bg1.jpg', 1000, 1)",
                )
                // conversion_records (v3 schema — includes segment columns)
                st.execute(
                    """
                    CREATE TABLE `conversion_records` (
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
                st.execute(
                    "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                        "VALUES ('content://audio/a', 'content://video/v', 99999)",
                )
                // upload_records (v2 schema)
                st.execute(
                    """
                    CREATE TABLE `upload_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `youtubeVideoId` TEXT NOT NULL,
                        `watchUrl` TEXT NOT NULL,
                        `uploadedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO `upload_records` (`videoUri`, `youtubeVideoId`, `watchUrl`, `uploadedAt`) " +
                        "VALUES ('content://video/v', 'yt123', 'https://youtu.be/yt123', 55555)",
                )
            }

            val supportDb = jdbcSupportSqlite(conn)
            // When — run the real Migration object
            AppDatabase.MIGRATION_3_4.migrate(supportDb)

            // Then — existing rows in all three pre-existing tables are preserved
            conn.createStatement().use { st ->
                st.executeQuery("SELECT filePath FROM backgrounds").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("/files/bg1.jpg", rs.getString(1))
                    assertTrue(!rs.next())
                }
                st.executeQuery("SELECT audioUri, videoUri FROM conversion_records").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("content://audio/a", rs.getString(1))
                    assertEquals("content://video/v", rs.getString(2))
                    assertTrue(!rs.next())
                }
                st.executeQuery("SELECT youtubeVideoId FROM upload_records").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("yt123", rs.getString(1))
                    assertTrue(!rs.next())
                }
                // And the new table exists and is empty
                st.executeQuery("SELECT COUNT(*) FROM error_log_entries").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun success_migration4_5_versionsAndCreateSql() {
        // Given
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    require(args != null && args.isNotEmpty())
                    executed += args[0] as String
                    null
                }
                else -> defaultProxyReturn(method.returnType)
            }
        } as SupportSQLiteDatabase

        // When
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
        AppDatabase.MIGRATION_4_5.migrate(db)

        // Then — one CREATE TABLE IF NOT EXISTS for recording_records
        assertEquals(1, executed.size)
        val sql = executed[0]
        assertTrue("CREATE TABLE IF NOT EXISTS 구문 누락", sql.contains("CREATE TABLE IF NOT EXISTS"))
        assertTrue("테이블명 recording_records 누락", sql.contains("recording_records"))
        assertTrue(
            "id 컬럼 INTEGER PRIMARY KEY AUTOINCREMENT 누락",
            sql.contains("INTEGER PRIMARY KEY AUTOINCREMENT"),
        )
        assertTrue("filePath 컬럼 NOT NULL 누락", sql.contains("`filePath` TEXT NOT NULL"))
        assertTrue("format 컬럼 NOT NULL 누락", sql.contains("`format` TEXT NOT NULL"))
        assertTrue("durationMs 컬럼 NOT NULL 누락", sql.contains("`durationMs` INTEGER NOT NULL"))
        assertTrue("sizeBytes 컬럼 NOT NULL 누락", sql.contains("`sizeBytes` INTEGER NOT NULL"))
        assertTrue("createdAt 컬럼 NOT NULL 누락", sql.contains("`createdAt` INTEGER NOT NULL"))
    }

    @Test
    fun success_migration4_5_preservesExistingRowsOnRealSqlite() {
        // Given — real in-memory SQLite with v4 schema rows in all four pre-existing tables
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE `backgrounds` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        `isSelected` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO `backgrounds` (`filePath`, `addedAt`, `isSelected`) " +
                        "VALUES ('/files/bg1.jpg', 1000, 1)",
                )
                st.execute(
                    """
                    CREATE TABLE `conversion_records` (
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
                st.execute(
                    "INSERT INTO `conversion_records` (`audioUri`, `videoUri`, `createdAt`) " +
                        "VALUES ('content://audio/a', 'content://video/v', 99999)",
                )
                st.execute(
                    """
                    CREATE TABLE `upload_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `youtubeVideoId` TEXT NOT NULL,
                        `watchUrl` TEXT NOT NULL,
                        `uploadedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO `upload_records` (`videoUri`, `youtubeVideoId`, `watchUrl`, `uploadedAt`) " +
                        "VALUES ('content://video/v', 'yt123', 'https://youtu.be/yt123', 55555)",
                )
                st.execute(
                    """
                    CREATE TABLE `error_log_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `level` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `stackTrace` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO `error_log_entries` (`level`, `tag`, `message`, `createdAt`) " +
                        "VALUES ('E', 'Tag', 'msg', 777)",
                )
            }

            val supportDb = jdbcSupportSqlite(conn)
            // When
            AppDatabase.MIGRATION_4_5.migrate(supportDb)

            // Then — existing rows preserved + recording_records empty
            conn.createStatement().use { st ->
                st.executeQuery("SELECT filePath FROM backgrounds").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("/files/bg1.jpg", rs.getString(1))
                    assertTrue(!rs.next())
                }
                st.executeQuery("SELECT audioUri FROM conversion_records").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("content://audio/a", rs.getString(1))
                    assertTrue(!rs.next())
                }
                st.executeQuery("SELECT youtubeVideoId FROM upload_records").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("yt123", rs.getString(1))
                    assertTrue(!rs.next())
                }
                st.executeQuery("SELECT tag FROM error_log_entries").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Tag", rs.getString(1))
                    assertTrue(!rs.next())
                }
                st.executeQuery("SELECT COUNT(*) FROM recording_records").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    private fun jdbcSupportSqlite(conn: java.sql.Connection): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    require(args != null && args.isNotEmpty())
                    conn.createStatement().use { it.execute(args[0] as String) }
                    null
                }
                else -> defaultProxyReturn(method.returnType)
            }
        } as SupportSQLiteDatabase

    private fun defaultProxyReturn(returnType: Class<*>): Any? =
        when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Void.TYPE, Void.TYPE -> null
            else -> null
        }
}
