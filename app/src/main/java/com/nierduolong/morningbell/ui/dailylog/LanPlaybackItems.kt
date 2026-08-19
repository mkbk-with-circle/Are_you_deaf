package com.nierduolong.morningbell.ui.dailylog

import android.net.Uri
import com.nierduolong.morningbell.data.db.DailyLogEntity
import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.dailylog.lan.LanEndpoint
import com.nierduolong.morningbell.dailylog.lan.LanEndpointRegistry
import com.nierduolong.morningbell.dailylog.lan.LanVideoReference
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import java.io.File
import java.net.InetAddress

internal fun playbackItemsFor(
    clips: List<LogClipEntity>,
    log: DailyLogEntity?,
    session: NearbySessionCoordinator.State,
): List<PlaybackQueue.Item> {
    val endpoint =
        when {
            log == null -> null
            session is NearbySessionCoordinator.State.Hosting && session.logId == log.id ->
                LanEndpoint(InetAddress.getLoopbackAddress(), session.port, "local-host", null)
            else -> LanEndpointRegistry.get(log.id)
        }
    return clips.mapNotNull { clip ->
        if (clip.sourceKept && clip.filePath.isNotBlank() && File(clip.filePath).isFile) {
            PlaybackQueue.Item.Local(clip.filePath)
        } else {
            val uuid = clip.clientUuid ?: return@mapNotNull null
            val activeEndpoint = endpoint ?: return@mapNotNull null
            val code = log?.inviteCode ?: return@mapNotNull null
            val uri = Uri.parse("nierlan://log/${log.remoteId ?: log.id}/clip/$uuid")
            PlaybackQueue.Item.Remote(
                LanVideoReference(
                    uri = uri,
                    endpoint = activeEndpoint,
                    inviteCode = code,
                    clientUuid = uuid,
                ),
            )
        }
    }
}
