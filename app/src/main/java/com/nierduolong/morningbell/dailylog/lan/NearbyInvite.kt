package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 二维码只携带加入房间所需的最小信息。扫码不是鉴权替代品：加入时仍会连接当前 Wi-Fi
 * 中实际发现的主机，并核对 [remoteLogId] 后完成设备签名握手。
 */
data class NearbyInvite(
    val remoteLogId: String,
    val inviteCode: String,
    val logName: String,
    val ssid: String,
    val passphrase: String,
) {
    fun encode(): String =
        buildString {
            append("nierlog://join?v=1")
            append("&id=").append(remoteLogId.urlEncode())
            append("&code=").append(inviteCode)
            append("&name=").append(logName.urlEncode())
            append("&ssid=").append(ssid.urlEncode())
            append("&pass=").append(passphrase.urlEncode())
        }

    companion object {
        fun parse(raw: String?): NearbyInvite? {
            if (raw.isNullOrBlank() || raw.length > MAX_PAYLOAD_LENGTH) return null
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "nierlog" || uri.host != "join") return null
            val params =
                uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                    val equals = part.indexOf('=')
                    if (equals <= 0) null else part.substring(0, equals) to part.substring(equals + 1).urlDecode()
                }.toMap()
            if (params["v"] != "1") return null
            val id = params["id"]?.trim()?.takeIf { it.length in 8..128 } ?: return null
            val code = params["code"]?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
            return NearbyInvite(
                remoteLogId = id,
                inviteCode = code,
                logName = params["name"].orEmpty().trim().take(80).ifEmpty { "附近 Log" },
                ssid = params["ssid"].orEmpty().take(64),
                passphrase = params["pass"].orEmpty().take(128),
            )
        }

        private const val MAX_PAYLOAD_LENGTH = 2_048
    }
}

/** 跨“打开 Wi-Fi 设置”及进程重建保留一次待加入邀请；成功入房后立即清除。 */
object NearbyPendingInvite {
    private const val PREFS = "nearby_pending_invite"
    private const val KEY_PAYLOAD = "payload"
    private val mutableState = MutableStateFlow<NearbyInvite?>(null)
    val state: StateFlow<NearbyInvite?> = mutableState.asStateFlow()

    fun save(context: Context, invite: NearbyInvite) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PAYLOAD, invite.encode()).apply()
        mutableState.value = invite
    }

    fun peek(context: Context): NearbyInvite? {
        val stored = NearbyInvite.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAYLOAD, null))
        if (stored != null && mutableState.value == null) mutableState.value = stored
        return mutableState.value
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PAYLOAD).apply()
        mutableState.value = null
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun String.urlDecode(): String = runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrDefault("")
