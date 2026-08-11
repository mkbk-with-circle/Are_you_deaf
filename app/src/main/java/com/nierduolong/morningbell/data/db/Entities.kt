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
    /**
     * 用户已手动结束的上一次周期：对应「当年公历生日日期」的 epochDay。
     * 与当年 event 一致时表示本周期已处理，直到下一年再响。
     */
    val lastAcknowledgedEventEpochDay: Long? = null,
)

/**
 * Setlog 风格「每日日志」房间。当前版本仅本机一条 isPersonal=true 记录；
 * inviteCode/多人字段为 Phase 2（真正多人同步）预留，暂不启用邀请流程。
 */
@Entity(tableName = "daily_logs")
data class DailyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isPersonal: Boolean = true,
    val inviteCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 一条拍摄素材：时长不限（Setlog 原版 2–4 秒，此处按需求不设硬性上限） */
@Entity(
    tableName = "log_clips",
    indices = [Index(value = ["logId", "dayEpoch"])],
)
data class LogClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    /** LocalDate.toEpochDay() */
    val dayEpoch: Long,
    /** 相对 `dailylog/` 的路径；Repository 读出时会解析成本机绝对路径 */
    val filePath: String,
    val durationMs: Long,
    val caption: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 原始素材文件是否还在。按保留策略清理后置为 false：时间、说明、留言都留着，
     * 只是那一条视频已经被合成结果代替了。
     */
    val sourceKept: Boolean = true,
)

/** 某个 Log 某一天的自动合成结果（顺序拼接；多人分屏留待 Phase 2） */
@Entity(
    tableName = "daily_compilations",
    indices = [Index(value = ["logId", "dayEpoch"], unique = true)],
)
data class DailyCompilationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val dayEpoch: Long,
    /** 同 [LogClipEntity.filePath]：库里存相对路径 */
    val filePath: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 归档页用的按日聚合结果（非表，Room 查询投影）。
 * [lastClipAt] 用于判断某天的合成结果是否已过期（有新素材进来）。
 */
data class DayLogSummary(
    val dayEpoch: Long,
    val clipCount: Int,
    val totalDurationMs: Long,
    val lastClipAt: Long,
    /** 原始素材还在的条数；为 0 表示这一天已按保留策略精简，只剩合成结果 */
    val keptCount: Int,
    /**
     * 归档网格的封面：当天第一条**未被清理**的素材。
     * 全部素材都已清理时为 null，由 Repository 回落到当天的合成结果。
     */
    val coverPath: String?,
)

/** clip 下的留言；本机版仅自己留言，字段结构为 Phase 2 多人评论预留 */
@Entity(
    tableName = "log_comments",
    indices = [Index(value = ["clipId"])],
)
data class LogCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clipId: Long,
    val authorName: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

