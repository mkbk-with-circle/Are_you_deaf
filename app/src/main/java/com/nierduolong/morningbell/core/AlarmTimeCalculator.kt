package com.nierduolong.morningbell.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 计算下一次触发时间（本地时区），重复日为周日=0 … 周六=6 */
object AlarmTimeCalculator {
    fun parseRepeatDays(csv: String): Set<Int> =
        csv.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun formatRepeatDays(days: Set<Int>): String = days.sorted().joinToString(",")

    /** 下一次触发毫秒（epoch） */
    fun nextTriggerMillis(
        hour: Int,
        minute: Int,
        repeatCsv: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val days = parseRepeatDays(repeatCsv).ifEmpty { (0..6).toSet() }
        val now = ZonedDateTime.now(zone)
        val targetTime = LocalTime.of(hour, minute)
        for (offset in 0L..400L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.toSun0() !in days) continue
            val zdt = ZonedDateTime.of(date, targetTime, zone)
            if (zdt.isAfter(now)) return zdt.toInstant().toEpochMilli()
        }
        return null
    }

    /**
     * 严格晚于 [minExclusiveEpochMillis] 的第一次触发（用于「今日已截断连锁」后重排）。
     */
    fun nextTriggerMillisAfter(
        hour: Int,
        minute: Int,
        repeatCsv: String,
        minExclusiveEpochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val days = parseRepeatDays(repeatCsv).ifEmpty { (0..6).toSet() }
        val startDate = Instant.ofEpochMilli(minExclusiveEpochMillis).atZone(zone).toLocalDate()
        for (offset in 0L..400L) {
            val date = startDate.plusDays(offset)
            if (date.toSun0() !in days) continue
            val zdt = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            val ms = zdt.toInstant().toEpochMilli()
            if (ms > minExclusiveEpochMillis) return ms
        }
        return null
    }

    fun snoozeEpochMillis(minutes: Long = 5): Long =
        System.currentTimeMillis() + minutes * 60_000

    private fun LocalDate.toSun0(): Int =
        when (dayOfWeek) {
            DayOfWeek.SUNDAY -> 0
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
        }
}
