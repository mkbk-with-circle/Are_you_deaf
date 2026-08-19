package com.nierduolong.morningbell.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证正式用户从最后一个单人版 schema 升级后，旧日志仍在且多人字段默认值正确。 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    fun migrate10To11PreservesDailyLogDataAndCreatesSyncTables() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                "INSERT INTO daily_logs (id, name, isPersonal, inviteCode, createdAt) " +
                    "VALUES (7, '旧日志', 1, 'old-code', 1000)",
            )
            execSQL(
                "INSERT INTO log_clips " +
                    "(id, logId, dayEpoch, filePath, durationMs, caption, createdAt, sourceKept) " +
                    "VALUES (11, 7, 20000, 'clips/old.mp4', 2300, '旧片段', 1100, 1)",
            )
            execSQL(
                "INSERT INTO log_comments (id, clipId, authorName, text, createdAt) " +
                    "VALUES (13, 11, '旧用户', '旧留言', 1200)",
            )
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                11,
                true,
                AppDatabase.MIGRATION_10_11,
            )

        migrated.query(
            "SELECT name, inviteCode, role, memberCount, lastSyncCursor, remoteId " +
                "FROM daily_logs WHERE id = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧日志", cursor.getString(0))
            assertEquals("old-code", cursor.getString(1))
            assertEquals("personal", cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(0L, cursor.getLong(4))
            assertTrue(cursor.isNull(5))
        }
        migrated.query(
            "SELECT filePath, caption, sourceKept, transferState, clientUuid " +
                "FROM log_clips WHERE id = 11",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("clips/old.mp4", cursor.getString(0))
            assertEquals("旧片段", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("local", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }
        migrated.query(
            "SELECT authorName, text, deleted, clientUuid FROM log_comments WHERE id = 13",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧用户", cursor.getString(0))
            assertEquals("旧留言", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('log_members', 'sync_outbox', 'sync_events')",
        ).use { cursor ->
            val tables = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertEquals(setOf("log_members", "sync_outbox", "sync_events"), tables)
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_log_clips_logId_clientUuid'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-10-11"
    }
}
