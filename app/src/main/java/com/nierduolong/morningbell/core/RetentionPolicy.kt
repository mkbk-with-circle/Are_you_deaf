package com.nierduolong.morningbell.core

/**
 * 原始素材保留策略的纯逻辑。
 *
 * 不限时长的日志素材是占用的绝对大头，而一天的价值在合成结果里已经保住了，
 * 所以过了保留期就可以只留合成、删掉原始素材。这里只算「哪一天到期了」，
 * 真正删文件前还要由调用方确认那一天的合成结果确实存在且可播。
 */
object RetentionPolicy {
    /** 0 表示永久保留；其余为保留天数 */
    val OPTION_DAYS = listOf(0, 7, 30)

    fun normalizeDays(days: Int): Int = if (days in OPTION_DAYS) days else 0

    /** 不晚于这一天的记录可以清理原始素材；关闭时返回 null */
    fun cutoffDay(
        today: Long,
        retentionDays: Int,
    ): Long? = if (retentionDays <= 0) null else today - retentionDays

    fun isEligible(
        dayEpoch: Long,
        today: Long,
        retentionDays: Int,
    ): Boolean {
        val cutoff = cutoffDay(today, retentionDays) ?: return false
        return dayEpoch <= cutoff
    }
}
