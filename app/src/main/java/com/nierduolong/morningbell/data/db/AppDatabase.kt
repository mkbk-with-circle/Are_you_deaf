package com.nierduolong.morningbell.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nierduolong.morningbell.BuildConfig

@Database(
    entities = [
        AlarmEntity::class,
        MoodEntity::class,
        GoalEntity::class,
        BirthdayEntity::class,
        BirthdayReminderEntity::class,
        ReminderTemplateEntity::class,
        ChainAlarmGroupEntity::class,
        ChainAlarmStepEntity::class,
        ChainDoneDayEntity::class,
        DailyLogEntity::class,
        LogClipEntity::class,
        DailyCompilationEntity::class,
        LogCommentEntity::class,
        LogMemberEntity::class,
        SyncOutboxEntity::class,
        SyncEventEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun moodDao(): MoodDao
    abstract fun goalDao(): GoalDao
    abstract fun birthdayDao(): BirthdayDao

    abstract fun reminderTemplateDao(): ReminderTemplateDao

    abstract fun chainAlarmDao(): ChainAlarmDao

    abstract fun dailyLogDao(): DailyLogDao

    companion object {
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE alarms ADD COLUMN soundUri TEXT")
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `micro_task_custom` " +
                            "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `micro_task_days` " +
                            "(`dayEpoch` INTEGER NOT NULL, `taskText` TEXT NOT NULL, " +
                            "`completed` INTEGER NOT NULL DEFAULT 0, " +
                            "`completedAtMillis` INTEGER, `swapCount` INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY(`dayEpoch`))",
                    )
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `chain_alarm_groups` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`enabled` INTEGER NOT NULL, `repeatDays` TEXT NOT NULL, " +
                            "`note` TEXT NOT NULL, `soundUri` TEXT)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `chain_alarm_steps` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`groupId` INTEGER NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                            "`hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, " +
                            "`silent` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_alarm_steps_groupId` ON `chain_alarm_steps` (`groupId`)")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_chain_alarm_steps_groupId_stepIndex` " +
                            "ON `chain_alarm_steps` (`groupId`, `stepIndex`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `chain_done_days` (" +
                            "`groupId` INTEGER NOT NULL, `dayEpoch` INTEGER NOT NULL, " +
                            "`doneAfterStepIndex` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`groupId`, `dayEpoch`))",
                    )
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE birthdays ADD COLUMN isLunar INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `reminder_templates` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`text` TEXT NOT NULL)",
                    )
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `micro_task_days`")
                    db.execSQL("DROP TABLE IF EXISTS `micro_task_custom`")
                }
            }

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `video_diary_entries` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`dayEpoch` INTEGER NOT NULL, " +
                            "`relativePath` TEXT NOT NULL, " +
                            "`displayName` TEXT NOT NULL, " +
                            "`sizeBytes` INTEGER NOT NULL, " +
                            "`addedAtMillis` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_video_diary_entries_dayEpoch` " +
                            "ON `video_diary_entries` (`dayEpoch`)",
                    )
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE birthday_reminders ADD COLUMN lastAcknowledgedEventEpochDay INTEGER",
                    )
                }
            }

        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `video_diary_entries`")
                    db.execSQL("DROP TABLE IF EXISTS `wake_days`")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_logs` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`isPersonal` INTEGER NOT NULL, " +
                            "`inviteCode` TEXT, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `log_clips` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`logId` INTEGER NOT NULL, " +
                            "`dayEpoch` INTEGER NOT NULL, " +
                            "`filePath` TEXT NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL, " +
                            "`caption` TEXT, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_log_clips_logId_dayEpoch` " +
                            "ON `log_clips` (`logId`, `dayEpoch`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_compilations` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`logId` INTEGER NOT NULL, " +
                            "`dayEpoch` INTEGER NOT NULL, " +
                            "`filePath` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_compilations_logId_dayEpoch` " +
                            "ON `daily_compilations` (`logId`, `dayEpoch`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `log_comments` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`clipId` INTEGER NOT NULL, " +
                            "`authorName` TEXT NOT NULL, " +
                            "`text` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_log_comments_clipId` " +
                            "ON `log_comments` (`clipId`)",
                    )
                }
            }

        /**
         * 保留策略需要区分「素材还在」与「已被合成结果代替」。
         * 默认 1（都还在），历史数据语义不变。
         */
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE log_clips ADD COLUMN sourceKept INTEGER NOT NULL DEFAULT 1")
                }
            }

        /**
         * 附近多人 Log 的本地优先模型。视频文件仍留在文件系统；Room 只记录身份、
         * 传输状态、幂等操作与主机事件游标，因此数据库迁移本身不会复制任何大文件。
         */
        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN remoteId TEXT")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN role TEXT NOT NULL DEFAULT 'personal'")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN hostDeviceId TEXT")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN memberCount INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN lastSyncCursor INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN lastSyncedAt INTEGER")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN lastHostAddress TEXT")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN lastHostPort INTEGER")
                    db.execSQL("ALTER TABLE daily_logs ADD COLUMN lastHostServiceName TEXT")

                    db.execSQL("ALTER TABLE log_clips ADD COLUMN clientUuid TEXT")
                    db.execSQL("ALTER TABLE log_clips ADD COLUMN remoteId TEXT")
                    db.execSQL("ALTER TABLE log_clips ADD COLUMN authorId TEXT")
                    db.execSQL("ALTER TABLE log_clips ADD COLUMN localThumbPath TEXT")
                    db.execSQL("ALTER TABLE log_clips ADD COLUMN transferState TEXT NOT NULL DEFAULT 'local'")
                    db.execSQL("ALTER TABLE log_clips ADD COLUMN contentSha256 TEXT")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_log_clips_logId_clientUuid` " +
                            "ON `log_clips` (`logId`, `clientUuid`)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_log_clips_logId_remoteId` " +
                            "ON `log_clips` (`logId`, `remoteId`)",
                    )

                    db.execSQL("ALTER TABLE log_comments ADD COLUMN clientUuid TEXT")
                    db.execSQL("ALTER TABLE log_comments ADD COLUMN remoteId TEXT")
                    db.execSQL("ALTER TABLE log_comments ADD COLUMN authorId TEXT")
                    db.execSQL("ALTER TABLE log_comments ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_log_comments_clientUuid` " +
                            "ON `log_comments` (`clientUuid`)",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `log_members` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`logId` INTEGER NOT NULL, `authorId` TEXT NOT NULL, " +
                            "`nickname` TEXT NOT NULL, `publicKey` TEXT NOT NULL, " +
                            "`avatarSeed` TEXT NOT NULL, `sourceAddress` TEXT, `sourcePort` INTEGER, " +
                            "`joinedAt` INTEGER NOT NULL, " +
                            "`lastSeenAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_log_members_logId_authorId` " +
                            "ON `log_members` (`logId`, `authorId`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logId` INTEGER NOT NULL, " +
                            "`operationId` TEXT NOT NULL, `entityType` TEXT NOT NULL, " +
                            "`entityClientUuid` TEXT NOT NULL, `operation` TEXT NOT NULL, " +
                            "`payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_operationId` " +
                            "ON `sync_outbox` (`operationId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_logId_nextAttemptAt` " +
                            "ON `sync_outbox` (`logId`, `nextAttemptAt`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_events` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logId` INTEGER NOT NULL, " +
                            "`operationId` TEXT NOT NULL, `entityType` TEXT NOT NULL, " +
                            "`operation` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_sync_events_logId_id` " +
                            "ON `sync_events` (`logId`, `id`)",
                    )
                }
            }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "morning_bell.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                )
                .apply {
                    // 推平重建只在开发期允许。正式包里漏写一次迁移就会静默清空用户
                    // 全部闹钟与日志记录，那种「不崩但数据没了」的故障远比启动崩溃难发现。
                    if (BuildConfig.DEBUG) fallbackToDestructiveMigration()
                }
                .build()
    }
}
