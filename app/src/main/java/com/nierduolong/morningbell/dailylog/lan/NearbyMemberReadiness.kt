package com.nierduolong.morningbell.dailylog.lan

import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.data.db.LogMemberEntity

data class NearbyMemberStatus(
    val authorId: String,
    val nickname: String,
    val online: Boolean,
    val clipCount: Int,
) {
    val ready: Boolean get() = clipCount > 0
}

/** 今日准备度完全由已有成员与素材元数据推导，不额外引入同步表或轮询大文件。 */
internal object NearbyMemberReadiness {
    const val ONLINE_WINDOW_MS = 45_000L

    fun calculate(
        members: List<LogMemberEntity>,
        clips: List<LogClipEntity>,
        hostDeviceId: String?,
        hostReachable: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<NearbyMemberStatus> {
        val counts = clips.mapNotNull { it.authorId }.groupingBy { it }.eachCount()
        return members.map { member ->
            NearbyMemberStatus(
                authorId = member.authorId,
                nickname = member.nickname,
                online =
                    (hostReachable && member.authorId == hostDeviceId) ||
                        now - member.lastSeenAt in 0..ONLINE_WINDOW_MS,
                clipCount = counts[member.authorId] ?: 0,
            )
        }.sortedWith(
            compareByDescending<NearbyMemberStatus> { it.authorId == hostDeviceId }
                .thenByDescending { it.online }
                .thenBy { it.nickname },
        )
    }
}
