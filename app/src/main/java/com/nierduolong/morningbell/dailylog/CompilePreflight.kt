package com.nierduolong.morningbell.dailylog

import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.dailylog.lan.LanEndpoint
import com.nierduolong.morningbell.dailylog.lan.LanEndpointRegistry
import com.nierduolong.morningbell.dailylog.lan.LanHostClient
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress

data class CompileMemberAvailability(
    val authorId: String?,
    val nickname: String,
    val totalClips: Int,
    val availableClips: Int,
) {
    val unavailableClips: Int get() = totalClips - availableClips
}

data class CompilePreflightReport(
    val totalClips: Int,
    val availableClips: Int,
    val unavailableClipIds: Set<Long>,
    val members: List<CompileMemberAvailability>,
) {
    val unavailableClips: Int get() = totalClips - availableClips
    val canCompile: Boolean get() = availableClips > 0
    val allAvailable: Boolean get() = unavailableClips == 0
}

/** 合成前只发至多四路并发 HEAD，不预取原片，也不产生任何临时视频。 */
object CompilePreflight {
    suspend fun inspect(
        repo: AppRepository,
        logId: Long,
        dayEpoch: Long,
    ): CompilePreflightReport =
        withContext(Dispatchers.IO) {
            val clips = repo.clipsForDay(logId, dayEpoch).sortedBy { it.createdAt }
            val log = repo.getDailyLog(logId)
            val members = repo.logMembers(logId).associateBy { it.authorId }
            val session = NearbySessionCoordinator.state.value
            val endpoint =
                when {
                    session is NearbySessionCoordinator.State.Hosting && session.logId == logId ->
                        LanEndpoint(InetAddress.getLoopbackAddress(), session.port, "local-host", network = null)
                    else -> LanEndpointRegistry.get(logId)
                }
            val client =
                if (endpoint != null && log?.inviteCode != null) LanHostClient(endpoint, log.inviteCode) else null
            val permit = Semaphore(MAX_PARALLEL_HEADS)
            val availability =
                coroutineScope {
                    clips.map { clip ->
                        async {
                            val uuid = clip.clientUuid
                            val local = clip.sourceKept && clip.filePath.isNotBlank() && File(clip.filePath).isFile
                            val available =
                                local ||
                                    (uuid != null && client != null && permit.withPermit { client.videoAvailable(uuid) })
                            clip.id to available
                        }
                    }.awaitAll()
                }
            val unavailable = availability.filter { !it.second }.map { it.first }.toSet()
            val groups = clips.groupBy { it.authorId }
            val memberRows =
                groups.map { (authorId, authoredClips) ->
                    val available =
                        authoredClips.count { clip ->
                            val local = clip.sourceKept && clip.filePath.isNotBlank() && File(clip.filePath).isFile
                            local || clip.id !in unavailable
                        }
                    CompileMemberAvailability(
                        authorId = authorId,
                        nickname = authorId?.let(members::get)?.nickname ?: if (authorId == null) "本机" else "成员 ${authorId.take(6)}",
                        totalClips = authoredClips.size,
                        availableClips = available,
                    )
                }.sortedWith(compareByDescending<CompileMemberAvailability> { it.availableClips }.thenBy { it.nickname })
            CompilePreflightReport(
                totalClips = clips.size,
                availableClips = clips.size - unavailable.size,
                unavailableClipIds = unavailable,
                members = memberRows,
            )
        }

    private const val MAX_PARALLEL_HEADS = 4
}
