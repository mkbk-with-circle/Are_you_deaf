package com.nierduolong.morningbell.core

import java.time.Instant
import java.time.ZoneId

/**
 * 拍摄提醒的下一次触发时刻计算：纯函数，不碰 AlarmManager，便于单测。
 * 关键约束是「活跃时段」——落在时段外的候选时刻会被顺延到下一个可提醒的时刻，
 * 否则每小时提醒会在凌晨把人叫醒。
 */
object ReminderWindowPolicy {
    fun nextTriggerAt(
        nowMillis: Long,
        intervalMinutes: Int,
        startHour: Int,
        endHour: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(start + 1, 24)
        val candidate =
            Instant.ofEpochMilli(nowMillis)
                .atZone(zone)
                .plusMinutes(intervalMinutes.coerceAtLeast(1).toLong())
                .withSecond(0)
                .withNano(0)

        val windowStartToday = candidate.withHour(start).withMinute(0)
        return when {
            candidate.hour < start -> windowStartToday
            candidate.hour >= end -> windowStartToday.plusDays(1)
            else -> candidate
        }.toInstant().toEpochMilli()
    }

    /** 当前时刻是否落在活跃时段内（用于「下一次提醒」文案与调试展示） */
    fun isWithinWindow(
        millis: Long,
        startHour: Int,
        endHour: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val hour = Instant.ofEpochMilli(millis).atZone(zone).hour
        return hour >= startHour.coerceIn(0, 23) && hour < endHour.coerceIn(1, 24)
    }
}
