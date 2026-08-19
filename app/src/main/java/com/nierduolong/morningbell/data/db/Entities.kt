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
 * Setlog 风格「每日日志」房间。个人 Log 与附近多人 Log 共用此表；附近模式由一台手机
 * 临时担任局域网主机，不依赖云端服务器。
 */
@Entity(tableName = "daily_logs")
data class DailyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isPersonal: Boolean = true,
    val inviteCode: String? = null,
    /** 跨设备稳定 id；本机个人日志可为空，附近多人 Log 创建时生成 UUID。 */
    val remoteId: String? = null,
    /** personal / owner / member。用字符串存库，便于后续协议演进。 */
    val role: String = "personal",
    /** 附近 Log 的权威节点设备 id；断线后用它判断是否仍是同一个房间。 */
    val hostDeviceId: String? = null,
    val memberCount: Int = 1,
    /** 已拉取到的主机事件游标。 */
    val lastSyncCursor: Long = 0,
    val lastSyncedAt: Long? = null,
    /** 最近一次发现的私网端点；只作重连提示，真正连接仍需验证地址属于当前 Wi-Fi。 */
    val lastHostAddress: String? = null,
    val lastHostPort: Int? = null,
    val lastHostServiceName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 一条拍摄素材：时长不限（Setlog 原版 2–4 秒，此处按需求不设硬性上限） */
@Entity(
    tableName = "log_clips",
    indices = [
        Index(value = ["logId", "dayEpoch"]),
        Index(value = ["logId", "clientUuid"], unique = true),
        Index(value = ["logId", "remoteId"], unique = true),
    ],
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
    /** 拍摄设备生成的幂等键；上传重试不能生成重复素材。 */
    val clientUuid: String? = null,
    val remoteId: String? = null,
    val authorId: String? = null,
    /** 远端素材尚未下载时，缩略图仍可单独落盘展示。 */
    val localThumbPath: String? = null,
    /** local / metadata_only / available_remote / transferring / failed。 */
    val transferState: String = "local",
    /** 完整 MP4 的 SHA-256；流式传输完成后用于端到端校验。 */
    val contentSha256: String? = null,
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
    /** 远端素材没有本地视频时使用的小型 JPEG 缓存。 */
    val coverThumbPath: String?,
)

/** clip 下的留言；本机版仅自己留言，字段结构为 Phase 2 多人评论预留 */
@Entity(
    tableName = "log_comments",
    indices = [
        Index(value = ["clipId"]),
        Index(value = ["clientUuid"], unique = true),
    ],
)
data class LogCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clipId: Long,
    val authorName: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val clientUuid: String? = null,
    val remoteId: String? = null,
    val authorId: String? = null,
    /** 同步删除必须保留墓碑，否则离线成员会把留言重新带回来。 */
    val deleted: Boolean = false,
)

/** 附近 Log 成员。设备身份由 Android Keystore 公钥稳定标识，不依赖手机号或云账号。 */
@Entity(
    tableName = "log_members",
    indices = [Index(value = ["logId", "authorId"], unique = true)],
)
data class LogMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val authorId: String,
    val nickname: String,
    val publicKey: String,
    val avatarSeed: String,
    /** 由主机从已认证请求的 socket 记录，客户端不能伪造任意公网地址。 */
    val sourceAddress: String? = null,
    val sourcePort: Int? = null,
    val joinedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
)

/** 本机尚未送达主机的操作。payload 只放小元数据，视频本体永远不进入 SQLite。 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["operationId"], unique = true),
        Index(value = ["logId", "nextAttemptAt"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val operationId: String,
    val entityType: String,
    val entityClientUuid: String,
    val operation: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
)

/** 主机发布给成员的单调事件流；删除也作为事件发送，客户端按 cursor 增量拉取。 */
@Entity(
    tableName = "sync_events",
    indices = [Index(value = ["logId", "id"])],
)
data class SyncEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val operationId: String,
    val entityType: String,
    val operation: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)
