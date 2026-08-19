package com.nierduolong.morningbell.dailylog.lan

import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.data.db.SyncEventEntity
import com.nierduolong.morningbell.data.db.SyncOutboxEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 小 JSON 请求客户端。socket 优先由 NSD 返回的 Wi-Fi [android.net.Network] 创建，不会回落蜂窝网。 */
class LanHostClient(
    private val endpoint: LanEndpoint,
    private val inviteCode: String,
    private val identity: DeviceIdentity.PublicIdentity? = null,
) {
    data class JoinResult(
        val remoteLogId: String,
        val name: String,
        val hostDeviceId: String?,
        val memberCount: Int,
        val members: List<MemberSnapshot>,
    )

    data class MemberSnapshot(
        val authorId: String,
        val nickname: String,
        val lastSeenAt: Long,
        val online: Boolean,
    )

    data class RemoteClip(
        val clientUuid: String,
        val authorId: String?,
        val durationMs: Long,
        val caption: String?,
        val createdAt: Long,
        val contentSha256: String?,
    )

    data class EventBatch(
        val cursor: Long,
        val events: List<SyncEventEntity>,
    )

    fun join(
        identity: DeviceIdentity.PublicIdentity,
        nickname: String,
    ): JoinResult {
        val body =
            JSONObject()
                .put("authorId", identity.deviceId)
                .put("nickname", nickname)
                .put("publicKey", identity.publicKeyBase64)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        val json = request("POST", "/v1/join", body)
        return parseJoinResult(json)
    }

    fun logInfo(): JSONObject = request("GET", "/v1/log", null)

    fun heartbeat(authorId: String): JoinResult {
        val body = JSONObject().put("authorId", authorId).toString().toByteArray(StandardCharsets.UTF_8)
        return parseJoinResult(request("POST", "/v1/heartbeat", body))
    }

    /** 合成前只读取响应头确认源文件在线，不读取任何视频字节。 */
    fun videoAvailable(clientUuid: String): Boolean {
        val encoded = URLEncoder.encode(clientUuid, StandardCharsets.UTF_8.name())
        return runCatching {
            createSocket().use { socket ->
                socket.soTimeout = AVAILABILITY_TIMEOUT_MS
                val request =
                    buildString {
                        append("HEAD /v1/clips/$encoded/video HTTP/1.1\r\n")
                        append("Host: ${endpoint.host.hostAddress}:${endpoint.port}\r\n")
                        append("X-Invite-Code: $inviteCode\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                socket.getOutputStream().flush()
                val input = BufferedInputStream(socket.getInputStream())
                val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return false
                var length: Long? = null
                while (true) {
                    val line = readLine(input) ?: return false
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                        length = line.substring(colon + 1).trim().toLongOrNull()
                    }
                }
                status in 200..299 && (length ?: 0L) > 0L
            }
        }.getOrDefault(false)
    }

    fun registerSource(
        authorId: String,
        port: Int,
    ) {
        val body = JSONObject().put("authorId", authorId).put("port", port).toString().toByteArray(StandardCharsets.UTF_8)
        request("POST", "/v1/source", body)
    }

    fun publishClips(
        dayEpoch: Long,
        clips: List<LogClipEntity>,
        authorId: String,
    ): Int {
        val body =
            JSONObject()
                .put("dayEpoch", dayEpoch)
                .put(
                    "clips",
                    JSONArray().apply {
                        clips.take(500).forEach { clip ->
                            val uuid = clip.clientUuid ?: return@forEach
                            put(
                                JSONObject()
                                    .put("clientUuid", uuid)
                                    .put("authorId", clip.authorId ?: authorId)
                                    .put("durationMs", clip.durationMs)
                                    .put("caption", clip.caption)
                                    .put("createdAt", clip.createdAt)
                                    .put("contentSha256", clip.contentSha256),
                            )
                        }
                    },
                ).toString()
                .toByteArray(StandardCharsets.UTF_8)
        return request("POST", "/v1/clips", body).optInt("accepted")
    }

    fun clipsForDay(dayEpoch: Long): List<RemoteClip> {
        val array = request("GET", "/v1/clips?dayEpoch=$dayEpoch", null).getJSONArray("clips")
        return buildList {
            for (index in 0 until array.length()) {
                val clip = array.optJSONObject(index) ?: continue
                val uuid = clip.optString("clientUuid").takeIf { it.isNotBlank() && it != "null" } ?: continue
                add(
                    RemoteClip(
                        clientUuid = uuid,
                        authorId = clip.optString("authorId").takeIf { it.isNotBlank() && it != "null" },
                        durationMs = clip.optLong("durationMs"),
                        caption = clip.optString("caption").takeIf { it.isNotBlank() && it != "null" },
                        createdAt = clip.optLong("createdAt"),
                        contentSha256 = clip.optString("contentSha256").takeIf { it.length == 64 },
                    ),
                )
            }
        }
    }

    fun postOperation(
        actorId: String,
        item: SyncOutboxEntity,
    ) {
        val body =
            JSONObject()
                .put("actorId", actorId)
                .put("operationId", item.operationId)
                .put("entityType", item.entityType)
                .put("operation", item.operation)
                .put("payload", JSONObject(item.payloadJson))
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        request("POST", "/v1/operations", body)
    }

    fun eventsAfter(cursor: Long): EventBatch {
        val json = request("GET", "/v1/events?after=${cursor.coerceAtLeast(0)}", null)
        val array = json.getJSONArray("events")
        val events = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    SyncEventEntity(
                        id = item.optLong("cursor"),
                        logId = 0,
                        operationId = item.optString("operationId"),
                        entityType = item.optString("entityType"),
                        operation = item.optString("operation"),
                        payloadJson = item.optJSONObject("payload")?.toString() ?: "{}",
                        createdAt = item.optLong("createdAt"),
                    ),
                )
            }
        }
        return EventBatch(json.optLong("cursor", cursor), events)
    }

    /** 缩略图最多 2 MiB，边收边写临时文件；成功后原子替换，不会把半张图暴露给界面。 */
    fun downloadThumbnail(
        clientUuid: String,
        target: File,
    ): Boolean {
        val encoded = URLEncoder.encode(clientUuid, StandardCharsets.UTF_8.name())
        val socket = createSocket()
        val temp = File(target.parentFile, "${target.name}.part-${Thread.currentThread().id}")
        return try {
            socket.use {
                it.soTimeout = TIMEOUT_MS
                val request =
                    buildString {
                        append("GET /v1/clips/$encoded/thumb HTTP/1.1\r\n")
                        append("Host: ${endpoint.host.hostAddress}:${endpoint.port}\r\n")
                        append("X-Invite-Code: $inviteCode\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                it.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                it.getOutputStream().flush()
                val input = BufferedInputStream(it.getInputStream())
                val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return false
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return false
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toLongOrNull()?.takeIf { value -> value in 1..MAX_THUMB_BYTES }
                    ?: return false
                if (status != 200) return false
                target.parentFile?.mkdirs()
                temp.outputStream().buffered().use { output -> LanStreamRelay.copyExactly(input, output, length) }
                if (temp.length() != length) return false
                if (target.exists() && !target.delete()) return false
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
    ): JSONObject {
        val socket = createSocket()
        socket.use {
            it.soTimeout = TIMEOUT_MS
            val output = it.getOutputStream()
            val headers =
                buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: ${endpoint.host.hostAddress}:${endpoint.port}\r\n")
                    append("X-Invite-Code: $inviteCode\r\n")
                    identity?.let { current ->
                        val timestamp = System.currentTimeMillis()
                        append("X-Author-Id: ${current.deviceId}\r\n")
                        append("X-Request-Time: $timestamp\r\n")
                        append("X-Request-Signature: ${DeviceIdentity.signRequest(method, path, timestamp, body)}\r\n")
                    }
                    append("Connection: close\r\n")
                    if (body != null) {
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${body.size}\r\n")
                    }
                    append("\r\n")
                }
            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
            if (body != null) output.write(body)
            output.flush()
            return readJsonResponse(BufferedInputStream(it.getInputStream()))
        }
    }

    private fun parseJoinResult(json: JSONObject): JoinResult {
        val memberArray = json.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (index in 0 until memberArray.length()) {
                val item = memberArray.optJSONObject(index) ?: continue
                val authorId = item.optString("authorId").takeIf { it.isNotBlank() && it != "null" } ?: continue
                add(
                    MemberSnapshot(
                        authorId = authorId,
                        nickname = item.optString("nickname").takeIf { it.isNotBlank() } ?: "成员",
                        lastSeenAt = item.optLong("lastSeenAt", 0L),
                        online = item.optBoolean("online", false),
                    ),
                )
            }
        }
        return JoinResult(
            remoteLogId = json.getString("id"),
            name = json.getString("name"),
            hostDeviceId = json.optString("hostDeviceId").takeIf { it.isNotBlank() && it != "null" },
            memberCount = json.optInt("memberCount", members.size.coerceAtLeast(1)),
            members = members,
        )
    }

    private fun createSocket(): Socket {
        endpoint.network?.let { return it.socketFactory.createSocket(endpoint.host, endpoint.port) }
        return Socket().apply { connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS) }
    }

    private fun readJsonResponse(input: BufferedInputStream): JSONObject {
        val statusLine = readLine(input) ?: error("主机没有响应")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("主机响应格式错误")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: error("响应头不完整")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 0..MAX_JSON_BYTES }
            ?: error("响应体过大或缺少长度")
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) error("响应体不完整")
            offset += count
        }
        val json = JSONObject(String(body, StandardCharsets.UTF_8))
        if (status !in 200..299) error(json.optString("error", "主机拒绝请求（$status）"))
        return json
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val AVAILABILITY_TIMEOUT_MS = 4_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val MAX_THUMB_BYTES = 2L * 1024 * 1024
    }
}
