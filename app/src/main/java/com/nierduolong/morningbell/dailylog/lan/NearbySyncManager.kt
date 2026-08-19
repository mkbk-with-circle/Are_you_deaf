package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.dailylog.DailyLogStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 当前热点在线时的轻量同步；失败不影响拍摄，数据仍在本地，下次进入附近页可重试。 */
object NearbySyncManager {
    private val syncLock = Mutex()

    suspend fun publishAndPullDay(
        context: Context,
        repository: AppRepository,
        logId: Long,
        dayEpoch: Long,
    ): Boolean = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val log = repository.getDailyLog(logId) ?: return@withContext false
            if (log.role == "owner") {
                repository.clipsForDay(logId, dayEpoch)
                    .filter { it.filePath.isNotBlank() }
                    .forEach { repository.ensureClipSha256(it) }
                return@withContext true
            }
            if (log.role != "member") return@withContext true
            val endpoint = LanEndpointRegistry.get(logId) ?: return@withContext false
            val inviteCode = log.inviteCode ?: return@withContext false
            val identity = DeviceIdentity.getOrCreate()
            val client = LanHostClient(endpoint, inviteCode, identity)
            val localClips =
                repository.clipsForDay(logId, dayEpoch)
                    .filter { it.filePath.isNotBlank() }
                    .map { repository.ensureClipSha256(it) }
            client.publishClips(dayEpoch, localClips, identity.deviceId)
            client.clipsForDay(dayEpoch).forEach { clip ->
                repository.upsertRemoteClipMetadata(
                    logId = logId,
                    clientUuid = clip.clientUuid,
                    authorId = clip.authorId,
                    dayEpoch = dayEpoch,
                    durationMs = clip.durationMs,
                    caption = clip.caption,
                    createdAt = clip.createdAt,
                    contentSha256 = clip.contentSha256,
                )
                val stored = repository.getClipByClientUuid(logId, clip.clientUuid)
                val thumbnailMissing = stored?.localThumbPath.isNullOrBlank() || !java.io.File(stored?.localThumbPath.orEmpty()).isFile
                if (stored?.filePath.isNullOrBlank() && thumbnailMissing) {
                    val target = DailyLogStorage.remoteThumbnailFile(context, logId, clip.clientUuid)
                    if (client.downloadThumbnail(clip.clientUuid, target)) {
                        repository.saveRemoteThumbnail(logId, clip.clientUuid, target.absolutePath)
                    }
                }
            }
            for (item in repository.pendingSyncOperations(logId)) {
                val sent = runCatching { client.postOperation(identity.deviceId, item) }.isSuccess
                if (sent) {
                    repository.acknowledgeSyncOperation(item.operationId)
                } else {
                    repository.postponeSyncOperation(item)
                    break
                }
            }
            var cursor = log.lastSyncCursor
            var pages = 0
            while (pages < MAX_EVENT_PAGES) {
                val batch = client.eventsAfter(cursor)
                repository.applyRemoteSyncEvents(logId, batch.events, batch.cursor)
                cursor = batch.cursor
                pages++
                if (batch.events.size < EVENTS_PER_PAGE) break
            }
            true
        }
    }

    private const val EVENTS_PER_PAGE = 200
    private const val MAX_EVENT_PAGES = 5
}
