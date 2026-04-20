package com.nierduolong.morningbell.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
class AlarmTimeCalculatorTest {
    @Test
    fun nextTrigger_isAfterNow() {
        val zone = ZoneId.of("Asia/Shanghai")
        // 验证在有合法重复规则时能算出「晚于此刻」的下一次触发
        val next =
            AlarmTimeCalculator.nextTriggerMillis(
                hour = 13,
                minute = 0,
                repeatCsv = "0,1,2,3,4,5,6",
                zone = zone,
            )
        assertNotNull(next)
        assertTrue(next!! > System.currentTimeMillis() - 60_000L)
    }
}
