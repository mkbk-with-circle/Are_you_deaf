package com.nierduolong.morningbell.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionLogicTest {
    @Test
    fun wakeStreak_countsBackwardsFromTodayOrYesterday() {
        val today = 100L
        val set = setOf(today, today - 1, today - 2)
        assertEquals(3, CompanionLogic.computeWakeStreak(set, today))
    }

    @Test
    fun wakeStreak_skipsTodayIfEmpty() {
        val today = 50L
        val set = setOf(today - 1, today - 2)
        assertEquals(2, CompanionLogic.computeWakeStreak(set, today))
    }

    @Test
    fun moodTier_fromDistinctDaysInWindow() {
        val today = 200L
        val moods =
            (0..7).map { i ->
                com.nierduolong.morningbell.data.db.MoodEntity(
                    id = i.toLong(),
                    dayEpoch = today - i,
                    score = 3,
                )
            }
        assertEquals(2, CompanionLogic.moodBackgroundTier(moods, today))
    }
}
