package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
}
