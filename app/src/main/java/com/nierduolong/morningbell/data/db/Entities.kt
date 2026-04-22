package com.nierduolong.morningbell.data.db

import androidx.room.Entity
import androidx.room.Index
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
    /** 公历：1–12 月；农历：1–12 月（闰月暂不支持，与库一致） */
    val month: Int,
    /** 公历：1–31；农历：初一–三十 */
    val day: Int,
    /** true 时 month/day 按农历解释，提醒按当年对应公历日计算 */
    val isLunar: Boolean = false,
)

/** 用户自建的提醒文案模版（与内置列表合并展示） */
@Entity(tableName = "reminder_templates")
data class ReminderTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
)

@Entity(tableName = "birthday_reminders")
data class BirthdayReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val birthdayId: Long,
    /** 提前几天（0 表示生日当天早上） */
    val daysBefore: Int,
    val todoText: String,
)

/** 每日归档视频：文件在应用目录 VideoDiary/年/月/日/ 下，此处存元数据 */
@Entity(
    tableName = "video_diary_entries",
    indices = [Index(value = ["dayEpoch"])],
)
data class VideoDiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** LocalDate.toEpochDay()，与文件夹日期一致 */
    val dayEpoch: Long,
    /** 相对 VideoDiary 根的路径 */
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val addedAtMillis: Long,
)

