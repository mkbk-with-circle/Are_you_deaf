package com.nierduolong.morningbell.dailylog.lan

import com.nierduolong.morningbell.data.db.DailyLogEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownNearbyLogMatcherTest {
    private val known =
        DailyLogEntity(
            id = 7,
            name = "宿舍 Log",
            isPersonal = false,
            inviteCode = "123456",
            remoteId = "room-identity-abc",
            role = "member",
        )

    @Test
    fun reconnectsOnlyExactPreviouslyJoinedRoom() {
        assertTrue(KnownNearbyLogMatcher.matches(known, "room-identity-abc"))
        assertFalse(KnownNearbyLogMatcher.matches(known, "room-identity-other"))
        assertFalse(KnownNearbyLogMatcher.matches(known, null))
        assertFalse(KnownNearbyLogMatcher.matches(known, ""))
        assertFalse(KnownNearbyLogMatcher.matches(known, "null"))
    }

    @Test
    fun reconnectBackoffStartsFastAndCapsAtThirtySeconds() {
        assertEquals(3_000L, NearbyReconnectPolicy.delayMs(1))
        assertEquals(6_000L, NearbyReconnectPolicy.delayMs(2))
        assertEquals(30_000L, NearbyReconnectPolicy.delayMs(100))
    }
}
