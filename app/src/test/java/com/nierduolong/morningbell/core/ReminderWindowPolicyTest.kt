package com.nierduolong.morningbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderWindowPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millisOf(
        hour: Int,
        minute: Int,
    ): Long = LocalDateTime.of(2026, 3, 10, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun hourMinuteOf(millis: Long): Pair<Int, Int> {
        val t = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        return t.hour to t.minute
    }

    @Test
    fun insideWindow_keepsExactInterval() {
        val next = ReminderWindowPolicy.nextTriggerAt(millisOf(14, 20), 60, 9, 22, zone)
        assertEquals(15 to 20, hourMinuteOf(next))
    }

    @Test
    fun beforeWindow_movesToWindowStartSameDay() {
        val next = ReminderWindowPolicy.nextTriggerAt(millisOf(5, 0), 60, 9, 22, zone)
        assertEquals(9 to 0, hourMinuteOf(next))
    }

    @Test
    fun afterWindow_movesToNextDayWindowStart() {
        val next = ReminderWindowPolicy.nextTriggerAt(millisOf(21, 30), 60, 9, 22, zone)
        assertEquals(9 to 0, hourMinuteOf(next))
        val day = java.time.Instant.ofEpochMilli(next).atZone(zone).dayOfMonth
        assertEquals(11, day)
    }

    @Test
    fun exactlyAtWindowEnd_isTreatedAsOutside() {
        val next = ReminderWindowPolicy.nextTriggerAt(millisOf(21, 0), 60, 9, 22, zone)
        assertEquals(9 to 0, hourMinuteOf(next))
    }

    @Test
    fun degenerateWindow_isClampedInsteadOfLooping() {
        val next = ReminderWindowPolicy.nextTriggerAt(millisOf(12, 0), 60, 10, 10, zone)
        assertTrue(next > millisOf(12, 0))
    }

    @Test
    fun withinWindow_checksHourBounds() {
        assertTrue(ReminderWindowPolicy.isWithinWindow(millisOf(9, 0), 9, 22, zone))
        assertTrue(ReminderWindowPolicy.isWithinWindow(millisOf(21, 59), 9, 22, zone))
        assertFalse(ReminderWindowPolicy.isWithinWindow(millisOf(22, 0), 9, 22, zone))
        assertFalse(ReminderWindowPolicy.isWithinWindow(millisOf(8, 59), 9, 22, zone))
    }
}
