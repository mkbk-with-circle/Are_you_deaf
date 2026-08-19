package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.DailyLogEntity
import com.nierduolong.morningbell.data.db.LogMemberEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate

/**
 * App 回到前台时自动寻找曾加入过的附近 Log。首次加入仍需邀请码；成功一次后邀请码和
 * remoteLogId 已保存在本机，后续只会重连同一个房间，不会误连同网段里的其他服务。
 */
object NearbyAutoConnector {
    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class FoundUnjoined(val endpoint: LanEndpoint) : State
        data class Connecting(val logName: String) : State
        data class Reconnecting(val logName: String, val attempt: Int) : State
        data class WaitingForHotspot(val logName: String, val retryInSeconds: Int) : State
        data class Connected(val logId: Long, val logName: String) : State
        data class Failed(val message: String) : State
    }

    private val connectMutex = Mutex()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Activity 可见期间维持轻量心跳。断线时按退避周期重新发现，锁屏/切后台会随调用方
     * coroutine 一起停止，不让 mDNS 常驻耗电。
     */
    suspend fun supervise(
        context: Context,
        repository: AppRepository,
    ) {
        var knownLogs: List<DailyLogEntity>
        var initialDiscoveryDone = false
        var failures = 0
        var lastMetadataSyncAt = 0L
        while (currentCoroutineContext().isActive) {
            knownLogs = repository.nearbyMemberLogs()
            if (knownLogs.isEmpty()) {
                if (!initialDiscoveryDone) {
                    initialDiscoveryDone = true
                    reconnectKnownLog(context, repository)
                }
                // 用户可能在本次前台会话里完成首次扫码；挂起等待 Room 变化，不轮询、不重复跑 mDNS。
                repository.dailyLogsFlow.first { logs -> logs.any { it.role == "member" && it.remoteId != null } }
                continue
            }
            val healthy = knownLogs.firstOrNull { heartbeatKnownLog(repository, it) }
            if (healthy != null) {
                failures = 0
                mutableState.value = State.Connected(healthy.id, healthy.name)
                val now = System.currentTimeMillis()
                if (now - lastMetadataSyncAt >= METADATA_SYNC_INTERVAL_MS) {
                    runCatching {
                        NearbySyncManager.publishAndPullDay(
                            context,
                            repository,
                            healthy.id,
                            LocalDate.now().toEpochDay(),
                        )
                    }
                    lastMetadataSyncAt = now
                }
                delay(HEARTBEAT_INTERVAL_MS)
                continue
            }

            val target = knownLogs.firstOrNull() ?: return
            mutableState.value = State.Reconnecting(target.name, failures + 1)
            LanEndpointRegistry.remove(target.id)
            if (reconnectKnownLog(context, repository, RECONNECT_DISCOVERY_TIMEOUT_MS)) {
                failures = 0
                delay(HEARTBEAT_INTERVAL_MS)
                continue
            }

            failures++
            val retryMs = NearbyReconnectPolicy.delayMs(failures)
            mutableState.value = State.WaitingForHotspot(target.name, (retryMs / 1_000L).toInt())
            delay(retryMs)
        }
    }

    suspend fun reconnectKnownLog(
        context: Context,
        repository: AppRepository,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): Boolean = connectMutex.withLock {
        val knownLogs = repository.nearbyMemberLogs()
        val discovery = LanHostDiscovery(context.applicationContext)
        try {
            mutableState.value = State.Searching
            discovery.start()
            val endpoint =
                withTimeoutOrNull(timeoutMs) {
                    when (val found = discovery.state.first { it is LanHostDiscovery.State.Found || it is LanHostDiscovery.State.Failed }) {
                        is LanHostDiscovery.State.Found -> found.endpoint
                        is LanHostDiscovery.State.Failed -> null
                        else -> null
                    }
                }
            if (endpoint == null) {
                mutableState.value = State.Failed("当前 Wi-Fi 中没有发现已加入的附近 Log")
                return@withLock false
            }
            if (knownLogs.isEmpty()) {
                mutableState.value = State.FoundUnjoined(endpoint)
                return@withLock false
            }

            val identity = withContext(Dispatchers.IO) { DeviceIdentity.getOrCreate() }
            val nickname = repository.nicknameFlow.value
            for (known in knownLogs) {
                val code = known.inviteCode ?: continue
                val client = LanHostClient(endpoint, code, identity)
                val info = runCatching { withContext(Dispatchers.IO) { client.logInfo() } }.getOrNull() ?: continue
                if (!KnownNearbyLogMatcher.matches(known, info.optString("id"))) continue

                mutableState.value = State.Connecting(known.name)
                val connected =
                    runCatching {
                        NearbyConnectionManager.connect(
                            context = context,
                            repository = repository,
                            endpoint = endpoint,
                            inviteCode = code,
                            identity = identity,
                            nickname = nickname,
                        )
                    }.getOrElse {
                        mutableState.value = State.Failed(it.message ?: "附近 Log 自动连接失败")
                        return@withLock false
                    }
                mutableState.value = State.Connected(connected.id, connected.name)
                return@withLock true
            }

            // 可能是另一个新房间；保留端点供页面输入邀请码，不自动尝试加入。
            mutableState.value = State.FoundUnjoined(endpoint)
            false
        } finally {
            discovery.close()
            if (mutableState.value is State.Searching) mutableState.value = State.Idle
        }
    }

    internal fun markConnected(log: DailyLogEntity) {
        mutableState.value = State.Connected(log.id, log.name)
    }

    internal fun markConnecting(logName: String) {
        mutableState.value = State.Connecting(logName)
    }

    internal fun markFailed(message: String) {
        mutableState.value = State.Failed(message)
    }

    private suspend fun heartbeatKnownLog(
        repository: AppRepository,
        known: DailyLogEntity,
    ): Boolean {
        val endpoint = LanEndpointRegistry.get(known.id) ?: return false
        val code = known.inviteCode ?: return false
        val identity = withContext(Dispatchers.IO) { DeviceIdentity.getOrCreate() }
        val snapshot =
            runCatching {
                withContext(Dispatchers.IO) { LanHostClient(endpoint, code, identity).heartbeat(identity.deviceId) }
            }.getOrNull() ?: return false
        if (!KnownNearbyLogMatcher.matches(known, snapshot.remoteLogId)) return false
        syncMemberSnapshots(repository, known.id, snapshot, identity)
        return true
    }

    private const val DISCOVERY_TIMEOUT_MS = 15_000L
    private const val RECONNECT_DISCOVERY_TIMEOUT_MS = 8_000L
    private const val HEARTBEAT_INTERVAL_MS = 12_000L
    private const val METADATA_SYNC_INTERVAL_MS = 24_000L
}

internal object NearbyReconnectPolicy {
    private const val MIN_DELAY_MS = 3_000L
    const val MAX_DELAY_MS = 30_000L

    fun delayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 4)
        return (MIN_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}

internal object KnownNearbyLogMatcher {
    fun matches(known: DailyLogEntity, advertisedRemoteId: String?): Boolean {
        val advertisedId = advertisedRemoteId?.takeIf { it.isNotBlank() && it != "null" }
        return advertisedId != null && advertisedId == known.remoteId
    }
}

/** 手动首次加入与自动重连共用的完整握手，保证两条入口不会逐渐产生行为差异。 */
object NearbyConnectionManager {
    suspend fun connect(
        context: Context,
        repository: AppRepository,
        endpoint: LanEndpoint,
        inviteCode: String,
        identity: DeviceIdentity.PublicIdentity,
        nickname: String,
        expectedRemoteLogId: String? = null,
    ): DailyLogEntity {
        NearbyAutoConnector.markConnecting("附近 Log")
        val client = LanHostClient(endpoint, inviteCode, identity)
        val joined = withContext(Dispatchers.IO) { client.join(identity, nickname) }
        if (expectedRemoteLogId != null && joined.remoteLogId != expectedRemoteLogId) {
            error("当前热点中的 Log 与二维码不一致，请连接二维码所示热点")
        }
        val localLog =
            repository.joinNearbyDailyLog(
                remoteId = joined.remoteLogId,
                name = joined.name,
                hostDeviceId = joined.hostDeviceId,
                inviteCode = inviteCode,
                memberCount = joined.memberCount,
                hostAddress = endpoint.host.hostAddress ?: endpoint.host.toString(),
                hostPort = endpoint.port,
                hostServiceName = endpoint.serviceName,
            )
        syncMemberSnapshots(repository, localLog.id, joined, identity)
        LanEndpointRegistry.put(localLog.id, endpoint)
        NearbyLogPeerService.start(context, localLog.id, inviteCode)

        val ready =
            withTimeoutOrNull(SOURCE_START_TIMEOUT_MS) {
                NearbyPeerCoordinator.state.first {
                    it is NearbyPeerCoordinator.State.Ready && it.logId == localLog.id
                } as NearbyPeerCoordinator.State.Ready
            }
        if (ready != null) {
            withContext(Dispatchers.IO) { client.registerSource(identity.deviceId, ready.port) }
        }
        NearbySyncManager.publishAndPullDay(context, repository, localLog.id, LocalDate.now().toEpochDay())
        NearbyAutoConnector.markConnected(localLog)
        return localLog
    }

    private const val SOURCE_START_TIMEOUT_MS = 10_000L
}

private suspend fun syncMemberSnapshots(
    repository: AppRepository,
    localLogId: Long,
    joined: LanHostClient.JoinResult,
    identity: DeviceIdentity.PublicIdentity,
) {
    joined.members.forEach { member ->
        repository.upsertLogMember(
            LogMemberEntity(
                logId = localLogId,
                authorId = member.authorId,
                nickname = member.nickname,
                publicKey = if (member.authorId == identity.deviceId) identity.publicKeyBase64 else "",
                avatarSeed = member.authorId.take(12),
                lastSeenAt = if (member.online) member.lastSeenAt else 0L,
            ),
        )
    }
}
