package com.nierduolong.morningbell.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmEntity::class,
        MoodEntity::class,
        WakeDayEntity::class,
        GoalEntity::class,
        BirthdayEntity::class,
        BirthdayReminderEntity::class,
        MicroTaskCustomEntity::class,
        MicroTaskDayEntity::class,
        ChainAlarmGroupEntity::class,
        ChainAlarmStepEntity::class,
        ChainDoneDayEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun moodDao(): MoodDao
    abstract fun wakeDao(): WakeDao
    abstract fun goalDao(): GoalDao
    abstract fun birthdayDao(): BirthdayDao
    abstract fun microTaskDao(): MicroTaskDao

    abstract fun chainAlarmDao(): ChainAlarmDao

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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "morning_bell.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
