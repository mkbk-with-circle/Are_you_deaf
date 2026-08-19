package com.nierduolong.morningbell.dailylog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileFailureMessageTest {
    @Test
    fun turnsNetworkAndStorageFailuresIntoActions() {
        assertEquals(
            "成员设备在合成期间离线，请连接后重试",
            CompileFailureMessage.from(IllegalStateException("附近视频当前不可用（404）")),
        )
        assertEquals(
            "存储空间不足，清理后再试",
            CompileFailureMessage.from(IllegalStateException("No space left on device")),
        )
    }

    @Test
    fun boundsUnknownCodecMessages() {
        val result = CompileFailureMessage.from(IllegalStateException("x".repeat(500)))
        assertTrue(result.startsWith("合成失败："))
        assertTrue(result.length <= 85)
    }
}
