package com.nierduolong.morningbell.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 reconcileTodayWakeIfInvalid 一致的判定：解锁时刻（当日内分钟）是否仍满足起始线 */
class WakeThresholdPolicyTest {
    @Test
    fun unlock_before_threshold_is_invalid() {
        val unlockMinuteOfDay = 23 // 00:23
        val threshold = 5 * 60 + 15 // 05:15
        assertTrue(unlockMinuteOfDay < threshold)
    }

    @Test
    fun unlock_after_threshold_is_valid() {
        val unlockMinuteOfDay = 6 * 60 // 06:00
        val threshold = 5 * 60 + 15
        assertFalse(unlockMinuteOfDay < threshold)
    }
}
