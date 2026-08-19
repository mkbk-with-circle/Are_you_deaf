package com.nierduolong.morningbell.dailylog.lan

import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.data.db.LogMemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMemberReadinessTest {
    @Test
    fun countsTodayClipsAndKeepsReachableHostFirst() {
        val now = 1_000_000L
        val members =
            listOf(
                member("peer", "小林", now - 60_000L),
                member("host", "小余", 1L),
                member("ready-peer", "小王", now - 5_000L),
            )
        val clips =
            listOf(
                clip("ready-peer", "clip-a"),
                clip("ready-peer", "clip-b"),
                clip(null, "personal-clip"),
            )

        val result = NearbyMemberReadiness.calculate(members, clips, "host", hostReachable = true, now = now)

        assertEquals("host", result.first().authorId)
        assertTrue(result.first().online)
        assertFalse(result.first().ready)
        assertEquals(2, result.first { it.authorId == "ready-peer" }.clipCount)
        assertTrue(result.first { it.authorId == "ready-peer" }.online)
        assertFalse(result.first { it.authorId == "peer" }.online)
    }

    private fun member(
        id: String,
        name: String,
        seen: Long,
    ) = LogMemberEntity(logId = 7, authorId = id, nickname = name, publicKey = "", avatarSeed = id, lastSeenAt = seen)

    private fun clip(
        authorId: String?,
        uuid: String,
    ) = LogClipEntity(logId = 7, dayEpoch = 1, filePath = "", durationMs = 2_000, clientUuid = uuid, authorId = authorId)
}
