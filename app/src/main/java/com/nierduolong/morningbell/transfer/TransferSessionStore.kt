package com.nierduolong.morningbell.transfer

import android.content.Context
import org.json.JSONObject

/** 仅为系统回收进程后的前台服务恢复；停止分享时立即删除，且会从云备份排除。 */
object TransferSessionStore {
    data class Session(
        val selection: TransferSelection,
        val mode: TransferNetworkMode,
        val token: String,
        val startedAt: Long,
    )

    fun save(context: Context, session: Session) {
        val json = JSONObject()
            .put("selection", TransferSelectionCodec.encode(session.selection))
            .put("mode", session.mode.name)
            .put("token", session.token)
            .put("startedAt", session.startedAt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): Session? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val startedAt = json.getLong("startedAt")
            if (System.currentTimeMillis() - startedAt > MAX_SESSION_AGE_MS) return null
            Session(
                selection = TransferSelectionCodec.decode(json.getString("selection")) ?: return null,
                mode = TransferNetworkMode.valueOf(json.getString("mode")),
                token = json.getString("token").takeIf { it.length >= 24 } ?: return null,
                startedAt = startedAt,
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    const val PREFS = "nearby_transfer_session"
    private const val KEY = "active"
    private const val MAX_SESSION_AGE_MS = 12L * 60 * 60 * 1000
}
