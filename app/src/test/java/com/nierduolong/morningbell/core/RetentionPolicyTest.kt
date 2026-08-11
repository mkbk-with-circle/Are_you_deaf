package com.nierduolong.morningbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    private val today = 20_000L

    @Test
    fun disabled_neverExpiresAnyDay() {
        assertNull(RetentionPolicy.cutoffDay(today, 0))
        assertFalse(RetentionPolicy.isEligible(today - 999, today, 0))
    }

    @Test
    fun daysInsideWindowAreKept() {
        assertFalse(RetentionPolicy.isEligible(today, today, 7))
        assertFalse(RetentionPolicy.isEligible(today - 6, today, 7))
    }

    @Test
    fun dayExactlyAtCutoffAndOlderAreCleaned() {
        assertTrue(RetentionPolicy.isEligible(today - 7, today, 7))
        assertTrue(RetentionPolicy.isEligible(today - 8, today, 7))
        assertTrue(RetentionPolicy.isEligible(today - 31, today, 30))
    }

    @Test
    fun futureDaysAreNeverCleaned() {
        assertFalse(RetentionPolicy.isEligible(today + 1, today, 7))
    }

    @Test
    fun unknownDayCountFallsBackToForever() {
        assertEquals(0, RetentionPolicy.normalizeDays(-5))
        assertEquals(0, RetentionPolicy.normalizeDays(3))
        assertEquals(7, RetentionPolicy.normalizeDays(7))
        assertEquals(30, RetentionPolicy.normalizeDays(30))
    }
}
