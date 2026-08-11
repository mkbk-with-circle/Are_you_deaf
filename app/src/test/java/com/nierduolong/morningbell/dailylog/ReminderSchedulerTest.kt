package com.nierduolong.morningbell.dailylog

import com.nierduolong.morningbell.data.DailyLogSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSchedulerTest {
    @Test
    fun intervalMinutes_hourly() {
        assertEquals(60, ReminderScheduler.intervalMinutes(DailyLogSettings.ReminderCadence.HOURLY))
    }

    @Test
    fun intervalMinutes_every3Hours() {
        assertEquals(180, ReminderScheduler.intervalMinutes(DailyLogSettings.ReminderCadence.EVERY_3_HOURS))
    }

    @Test
    fun intervalMinutes_offIsZero() {
        assertEquals(0, ReminderScheduler.intervalMinutes(DailyLogSettings.ReminderCadence.OFF))
    }

    @Test
    fun intervalMinutes_randomStaysInReasonableRange() {
        repeat(50) {
            val minutes = ReminderScheduler.intervalMinutes(DailyLogSettings.ReminderCadence.RANDOM)
            assert(minutes in 45..150) { "随机间隔 $minutes 超出 45–150 分钟" }
        }
    }
}
