package com.nierduolong.morningbell.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyLogStatsTest {
    @Test
    fun streak_countsConsecutiveDaysEndingToday() {
        val today = 1000L
        assertEquals(3, DailyLogStats.computeStreak(setOf(today, today - 1, today - 2), today))
    }

    @Test
    fun streak_todayNotRecordedYetStillCountsFromYesterday() {
        val today = 1000L
        assertEquals(2, DailyLogStats.computeStreak(setOf(today - 1, today - 2), today))
    }

    @Test
    fun streak_brokenWhenGapReachesTwoDays() {
        val today = 1000L
        assertEquals(0, DailyLogStats.computeStreak(setOf(today - 2, today - 3), today))
    }

    @Test
    fun streak_emptyHistoryIsZero() {
        assertEquals(0, DailyLogStats.computeStreak(emptySet(), 1000L))
    }

    @Test
    fun formatDuration_usesMinuteSecondsUnderOneHour() {
        assertEquals("0:05", DailyLogStats.formatDuration(5_400))
        assertEquals("2:03", DailyLogStats.formatDuration(123_000))
    }

    @Test
    fun formatDuration_usesHoursWhenNeeded() {
        assertEquals("1:01:01", DailyLogStats.formatDuration(3_661_000))
    }

    @Test
    fun formatBytes_scalesUnits() {
        assertEquals("512 B", DailyLogStats.formatBytes(512))
        assertEquals("1.5 MB", DailyLogStats.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", DailyLogStats.formatBytes(2L * 1024 * 1024 * 1024))
    }
}
