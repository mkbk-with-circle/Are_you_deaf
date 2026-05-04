package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** 生日卡片：用于单元测试与 UI 展示分离 */
object BirthdayReminderLogic {
    data class DueCard(
        val birthdayId: Long,
        val name: String,
        val reminderId: Long,
        val todoText: String,
        val daysBefore: Int,
        val isBirthDay: Boolean,
    )

    fun collectDueCards(
        today: LocalDate,
        birthdays: List<BirthdayEntity>,
        reminders: List<BirthdayReminderEntity>,
    ): List<DueCard> {
        val byBirthday = reminders.groupBy { it.birthdayId }
        val result = mutableListOf<DueCard>()
        for (b in birthdays) {
            val list = byBirthday[b.id].orEmpty()
            for (r in list) {
                val eventDate = LunarBirthdayCalendar.solarEventDateThisYear(b, today)
                val triggerDate = eventDate.minusDays(r.daysBefore.toLong())
                if (!triggerDate.isEqual(today)) continue
                val isBirthDay = r.daysBefore == 0
                result.add(
                    DueCard(
                        birthdayId = b.id,
                        name = b.name,
                        reminderId = r.id,
                        todoText = r.todoText,
                        daysBefore = r.daysBefore,
                        isBirthDay = isBirthDay,
                    ),
                )
            }
        }
        return result.sortedWith(compareBy({ !it.isBirthDay }, { it.name }))
    }

    /**
     * 下一次生日提醒响铃时间（epoch millis，本地 0 点）：
     * - 首响：trigger 日当天 0 点；
     * - 若该日 0 点已过（进程被杀等）：从触发日起每个日历日 0 点重试，直到生日日当天 0 点为止；
     * - 超过当年生日公历日仍无 ack 时，进入下一年周期；
     * - [BirthdayReminderEntity.lastAcknowledgedEventEpochDay] 等于当年 event 时跳过本年度。
     */
    fun nextBirthdayAlarmScheduleMillis(
        birthday: BirthdayEntity,
        reminder: BirthdayReminderEntity,
        zone: ZoneId,
        now: ZonedDateTime,
    ): Long? {
        val today = now.toLocalDate()
        val nowMillis = now.toInstant().toEpochMilli()
        val startYear = today.year - 1
        for (y in startYear..(today.year + 4)) {
            val refAnchor = LocalDate.of(y, 1, 1)
            val eventDate = LunarBirthdayCalendar.solarEventDateThisYear(birthday, refAnchor)
            val triggerDate = eventDate.minusDays(reminder.daysBefore.toLong())
            if (reminder.lastAcknowledgedEventEpochDay == eventDate.toEpochDay()) {
                continue
            }
            if (today.isAfter(eventDate)) {
                continue
            }
            if (today.isBefore(triggerDate)) {
                val fireAt = triggerDate.atStartOfDay(zone).toInstant().toEpochMilli()
                if (fireAt > nowMillis) {
                    return fireAt
                }
                continue
            }
            // 已在 [triggerDate, eventDate] 窗口内：找下一个尚未到来的本地 0 点
            var d = triggerDate
            while (!d.isAfter(eventDate)) {
                val t = d.atStartOfDay(zone).toInstant().toEpochMilli()
                if (t > nowMillis) {
                    return t
                }
                d = d.plusDays(1)
            }
            // 窗口内 0 点已全部过去（含生日日下午）：尝试下一年
        }
        return null
    }

    /** 与当前调度/ack 对齐的「当年」公历生日日期（用于写入 lastAcknowledged）。 */
    fun activeCycleEventDate(
        birthday: BirthdayEntity,
        reminder: BirthdayReminderEntity,
        zone: ZoneId,
        now: ZonedDateTime,
    ): LocalDate? {
        val today = now.toLocalDate()
        val startYear = today.year - 1
        for (y in startYear..(today.year + 4)) {
            val refAnchor = LocalDate.of(y, 1, 1)
            val eventDate = LunarBirthdayCalendar.solarEventDateThisYear(birthday, refAnchor)
            val triggerDate = eventDate.minusDays(reminder.daysBefore.toLong())
            if (reminder.lastAcknowledgedEventEpochDay == eventDate.toEpochDay()) {
                continue
            }
            if (today.isAfter(eventDate)) {
                continue
            }
            return eventDate
        }
        return null
    }

    /**
     * @deprecated 请使用 [nextBirthdayAlarmScheduleMillis]（含补发与按日重试）。
     */
    @Deprecated("使用 nextBirthdayAlarmScheduleMillis")
    fun nextReminderTriggerEpochMillisAtStartOfDay(
        birthday: BirthdayEntity,
        reminder: BirthdayReminderEntity,
        zone: ZoneId,
        now: ZonedDateTime,
    ): Long? = nextBirthdayAlarmScheduleMillis(birthday, reminder, zone, now)

    /** 距离截止日还剩多少天（可为负），无截止日返回 null */
    fun daysUntilDeadline(today: LocalDate, deadlineEpochDay: Long?): Long? {
        if (deadlineEpochDay == null) return null
        val end = LocalDate.ofEpochDay(deadlineEpochDay)
        return ChronoUnit.DAYS.between(today, end)
    }

}
