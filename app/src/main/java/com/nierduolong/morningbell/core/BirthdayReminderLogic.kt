package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import java.time.LocalDate
import java.time.YearMonth
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
                val eventDate = birthdayDateThisYear(b.month, b.day, today)
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

    /** 距离截止日还剩多少天（可为负），无截止日返回 null */
    fun daysUntilDeadline(today: LocalDate, deadlineEpochDay: Long?): Long? {
        if (deadlineEpochDay == null) return null
        val end = LocalDate.ofEpochDay(deadlineEpochDay)
        return ChronoUnit.DAYS.between(today, end)
    }

    private fun birthdayDateThisYear(month: Int, day: Int, reference: LocalDate): LocalDate {
        val ym = YearMonth.of(reference.year, month)
        val safeDay = day.coerceAtMost(ym.lengthOfMonth())
        return LocalDate.of(reference.year, month, safeDay)
    }
}
