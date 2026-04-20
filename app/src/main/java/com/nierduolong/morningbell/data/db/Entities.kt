package com.nierduolong.morningbell.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    /** 无声闹钟：界面与交互一致，仅不播放媒体音 */
    val silent: Boolean = false,
    val note: String = "",
    /** 重复：周日=0 … 周六=6，逗号分隔，如 "1,2,3,4,5" */
    val repeatDays: String = "0,1,2,3,4,5,6",
    /** 自定义铃声 `content://` Uri 字符串；null 表示系统默认闹钟音 */
    val soundUri: String? = null,
)

@Entity(tableName = "moods")
data class MoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** java.time.LocalDate.toEpochDay() */
    val dayEpoch: Long,
    /** 1–5 */
    val score: Int,
)

@Entity(tableName = "wake_days")
data class WakeDayEntity(
    @PrimaryKey val dayEpoch: Long,
    /** 当日首次解锁（6:00 之后）时间戳 */
    val firstUnlockMillis: Long,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val deadlineEpochDay: Long?,
    val completed: Boolean = false,
)

@Entity(tableName = "birthdays")
data class BirthdayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 1–12 */
    val month: Int,
    /** 1–31 */
    val day: Int,
)

@Entity(tableName = "birthday_reminders")
data class BirthdayReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val birthdayId: Long,
    /** 提前几天（0 表示生日当天早上） */
    val daysBefore: Int,
    val todoText: String,
)

/** 用户自建的早晨小任务文案，与内置池合并随机 */
@Entity(tableName = "micro_task_custom")
data class MicroTaskCustomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
)

/** 当日抽中的小任务及完成状态（换一条会更新 taskText / swapCount） */
@Entity(tableName = "micro_task_days")
data class MicroTaskDayEntity(
    @PrimaryKey val dayEpoch: Long,
    val taskText: String,
    val completed: Boolean = false,
    val completedAtMillis: Long? = null,
    val swapCount: Int = 0,
)
