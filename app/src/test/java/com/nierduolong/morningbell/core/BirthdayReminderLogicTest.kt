package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BirthdayReminderLogicTest {
    @Test
    fun collectDueCards_matchesAdvancedReminder() {
        val today = LocalDate.of(2026, 4, 12)
        val birthday =
            BirthdayEntity(
                id = 1L,
                name = "Ada",
                month = 4,
                day = 19,
            )
        val reminders =
            listOf(
                BirthdayReminderEntity(
                    id = 1L,
                    birthdayId = 1L,
                    daysBefore = 7,
                    todoText = "订蛋糕",
                ),
            )
        val due =
            BirthdayReminderLogic.collectDueCards(
                today = today,
                birthdays = listOf(birthday),
                reminders = reminders,
            )
        assertEquals(1, due.size)
        assertEquals("订蛋糕", due[0].todoText)
        assertEquals(false, due[0].isBirthDay)
    }

    @Test
    fun collectDueCards_birthDayFlag() {
        val today = LocalDate.of(2026, 4, 19)
        val birthday = BirthdayEntity(id = 2L, name = "Bob", month = 4, day = 19)
        val reminders =
            listOf(
                BirthdayReminderEntity(
                    id = 2L,
                    birthdayId = 2L,
                    daysBefore = 0,
                    todoText = "说生日快乐",
                ),
            )
        val due =
            BirthdayReminderLogic.collectDueCards(
                today = today,
                birthdays = listOf(birthday),
                reminders = reminders,
            )
        assertTrue(due.any { it.isBirthDay })
    }

    @Test
    fun nextTrigger_atStartOfDay_matchesDaysBeforeRule() {
        val zone = ZoneId.of("Asia/Shanghai")
        val birthday =
            BirthdayEntity(
                id = 1L,
                name = "Ada",
                month = 5,
                day = 15,
            )
        val reminder =
            BirthdayReminderEntity(
                id = 1L,
                birthdayId = 1L,
                daysBefore = 7,
                todoText = "买礼物",
            )
        val now = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, zone)
        val ms =
            BirthdayReminderLogic.nextBirthdayAlarmScheduleMillis(
                birthday,
                reminder,
                zone,
                now,
            )
        assertNotNull(ms)
        val triggerDay = Instant.ofEpochMilli(ms!!).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2026, 5, 8), triggerDay)
    }

    @Test
    fun nextBirthdayAlarm_missesFirstMidnight_thenNextDayMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val birthday = BirthdayEntity(1L, "Ada", 5, 15, false)
        val reminder = BirthdayReminderEntity(1L, 1L, 7, "买礼物")
        val now = ZonedDateTime.of(2026, 5, 8, 8, 0, 0, 0, zone)
        val ms =
            BirthdayReminderLogic.nextBirthdayAlarmScheduleMillis(
                birthday,
                reminder,
                zone,
                now,
            )
        assertNotNull(ms)
        assertEquals(LocalDate.of(2026, 5, 9), Instant.ofEpochMilli(ms!!).atZone(zone).toLocalDate())
    }

    @Test
    fun nextBirthdayAlarm_acked_thisYear_schedulesNextYearTrigger() {
        val zone = ZoneId.of("Asia/Shanghai")
        val birthday = BirthdayEntity(1L, "Ada", 5, 15, false)
        val event2026 =
            LunarBirthdayCalendar.solarEventDateThisYear(
                birthday,
                LocalDate.of(2026, 1, 1),
            )
        val reminder =
            BirthdayReminderEntity(
                id = 1L,
                birthdayId = 1L,
                daysBefore = 7,
                todoText = "买礼物",
                lastAcknowledgedEventEpochDay = event2026.toEpochDay(),
            )
        val now = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, zone)
        val ms =
            BirthdayReminderLogic.nextBirthdayAlarmScheduleMillis(
                birthday,
                reminder,
                zone,
                now,
            )
        assertNotNull(ms)
        assertEquals(LocalDate.of(2027, 5, 8), Instant.ofEpochMilli(ms!!).atZone(zone).toLocalDate())
    }
}
