package com.nierduolong.morningbell.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 连锁闹钟组：共用重复规则与铃声；每一步有独立时间与无声/震动 */
@Entity(tableName = "chain_alarm_groups")
data class ChainAlarmGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    /** 周日=0 … 周六=6 */
    val repeatDays: String = "0,1,2,3,4,5,6",
    val note: String = "",
    val soundUri: String? = null,
)

@Entity(
    tableName = "chain_alarm_steps",
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "stepIndex"], unique = true),
    ],
)
data class ChainAlarmStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    /** 0..n-1，按时间先后保存 */
    val stepIndex: Int,
    val hour: Int,
    val minute: Int,
    val silent: Boolean = false,
    val vibrate: Boolean = true,
)

/** 某日在某步点了「完成」后，当日更大 stepIndex 的步骤不再响 */
@Entity(
    tableName = "chain_done_days",
    primaryKeys = ["groupId", "dayEpoch"],
)
data class ChainDoneDayEntity(
    val groupId: Long,
    /** LocalDate.toEpochDay() */
    val dayEpoch: Long,
    val doneAfterStepIndex: Int,
)
