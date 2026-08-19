package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.LogMemberEntity
import com.nierduolong.morningbell.dailylog.DailyLogStorage
import com.nierduolong.morningbell.dailylog.ThumbnailStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * App 内嵌的最小局域网 HTTP 服务。它只接受私网/链路本地来源，视频响应直接从拥有者文件
 * 流向 socket；不会先读入 byte[]，也不会在热点主机生成第二份临时文件。
 */
class LanHostServer(
    private val context: Context,
    private val repository: AppRepository,
    private val logId: Long,
    private val inviteCode: String,
) : AutoCloseable {
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newFixedThreadPool(MAX_CLIENTS)
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    // 固定端口用于 mDNS 被厂商系统拦截时的“Wi-Fi 网关单点探测”；占用时仍可回落随机端口。
    private val server = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }

    val port: Int get() = server.localPort

    fun start() {
        acceptExecutor.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!isLanAddress(socket.inetAddress) || clients.size >= MAX_CLIENTS) {
                    runCatching { socket.close() }
                    continue
                }
                clients += socket
                clientExecutor.execute {
                    try {
                        handle(socket)
                    } finally {
                        clients -= socket
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = HEADER_TIMEOUT_MS
        val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
        val output = socket.getOutputStream()
        val path = request.target.substringBefore('?')

        if (request.method == "GET" && path == "/v1/health") {
            writeJson(output, 200, JSONObject().put("ok", true).put("protocol", 1))
            return
        }
        if (request.headers["x-invite-code"] != inviteCode) {
            writeJson(output, 401, JSONObject().put("error", "邀请码不正确"))
            return
        }
        val signedWrite = request.method == "POST" && path in setOf("/v1/clips", "/v1/source", "/v1/operations", "/v1/heartbeat")
        val actorId = if (signedWrite) authenticateMember(request) else null
        if (signedWrite && actorId == null) {
            writeJson(output, 401, JSONObject().put("error", "设备签名无效或已过期"))
            return
        }

        when {
            request.method == "GET" && path == "/v1/log" -> writeLog(output)
            request.method == "POST" && path == "/v1/join" -> join(request, output)
            request.method == "GET" && path == "/v1/clips" -> writeClips(request, output)
            request.method == "POST" && path == "/v1/clips" -> receiveClipMetadata(request, requireNotNull(actorId), output)
            request.method == "POST" && path == "/v1/operations" -> receiveOperation(request, requireNotNull(actorId), output)
            request.method == "GET" && path == "/v1/events" -> writeEvents(request, output)
            request.method == "POST" && path == "/v1/source" ->
                registerSource(request, requireNotNull(actorId), socket.inetAddress.hostAddress ?: socket.inetAddress.toString(), output)
            request.method == "POST" && path == "/v1/heartbeat" -> heartbeat(request, requireNotNull(actorId), output)
            (request.method == "GET" || request.method == "HEAD") &&
                path.startsWith("/v1/clips/") && path.endsWith("/video") -> streamClip(request, path, output)
            (request.method == "GET" || request.method == "HEAD") &&
                path.startsWith("/v1/clips/") && path.endsWith("/thumb") -> streamThumbnail(request, path, output)
            else -> writeJson(output, 404, JSONObject().put("error", "接口不存在"))
        }
    }

    private fun writeLog(output: OutputStream) {
        val log = runBlocking { repository.getDailyLog(logId) }
        if (log == null) {
            writeJson(output, 404, JSONObject().put("error", "Log 不存在"))
            return
        }
        val members = runBlocking { repository.logMembers(logId) }
        val now = System.currentTimeMillis()
        writeJson(
            output,
            200,
            JSONObject()
                .put("id", log.remoteId)
                .put("name", log.name)
                .put("hostDeviceId", log.hostDeviceId)
                .put("memberCount", members.size.coerceAtLeast(1))
                .put(
                    "members",
                    JSONArray().apply {
                        members.forEach { member ->
                            put(
                                JSONObject()
                                    .put("authorId", member.authorId)
                                    .put("nickname", member.nickname)
                                    .put("lastSeenAt", member.lastSeenAt)
                                    .put(
                                        "online",
                                        member.authorId == log.hostDeviceId ||
                                            (member.sourcePort != null && now - member.lastSeenAt <= MEMBER_ONLINE_WINDOW_MS),
                                    )
                            )
                        }
                    },
                ),
        )
    }

    private fun join(
        request: Request,
        output: OutputStream,
    ) {
        val json = runCatching { JSONObject(String(request.body, StandardCharsets.UTF_8)) }.getOrNull()
        val authorId = json?.optString("authorId")?.trim().orEmpty().take(MAX_AUTHOR_ID)
        val nickname = json?.optString("nickname")?.trim().orEmpty().take(MAX_NICKNAME)
        val publicKey = json?.optString("publicKey")?.trim().orEmpty().take(MAX_PUBLIC_KEY)
        if (authorId.isEmpty() || nickname.isEmpty() || publicKey.isEmpty()) {
            writeJson(output, 400, JSONObject().put("error", "成员信息不完整"))
            return
        }
        if (DeviceIdentity.deviceIdForPublicKey(publicKey) != authorId || !verifySignature(request, publicKey)) {
            writeJson(output, 401, JSONObject().put("error", "设备身份签名无效"))
            return
        }
        runBlocking {
            repository.upsertLogMember(
                LogMemberEntity(
                    logId = logId,
                    authorId = authorId,
                    nickname = nickname,
                    publicKey = publicKey,
                    avatarSeed = authorId.take(12),
                ),
            )
        }
        writeLog(output)
    }

    private fun writeClips(
        request: Request,
        output: OutputStream,
    ) {
        val day = queryParams(request.target)["dayEpoch"]?.toLongOrNull()
        if (day == null) {
            writeJson(output, 400, JSONObject().put("error", "缺少 dayEpoch"))
            return
        }
        val clips = runBlocking { repository.clipsForDay(logId, day) }
        val memberSources = runBlocking { repository.logMembers(logId) }.associateBy { it.authorId }
        val payload =
            JSONArray().apply {
                clips.forEach { clip ->
                    put(
                        JSONObject()
                            .put("clientUuid", clip.clientUuid)
                            .put("authorId", clip.authorId)
                            .put("durationMs", clip.durationMs)
                            .put("caption", clip.caption)
                            .put("createdAt", clip.createdAt)
                            .put(
                                "videoAvailable",
                                (clip.sourceKept && clip.filePath.isNotBlank() && File(clip.filePath).isFile) ||
                                    memberSources[clip.authorId]?.sourcePort != null,
                            )
                            .put("contentSha256", clip.contentSha256),
                    )
                }
            }
        writeJson(output, 200, JSONObject().put("dayEpoch", day).put("clips", payload))
    }

    private fun receiveClipMetadata(
        request: Request,
        actorId: String,
        output: OutputStream,
    ) {
        val json = runCatching { JSONObject(String(request.body, StandardCharsets.UTF_8)) }.getOrNull()
        val day = json?.optLong("dayEpoch", Long.MIN_VALUE) ?: Long.MIN_VALUE
        val clips = json?.optJSONArray("clips")
        if (day == Long.MIN_VALUE || clips == null || clips.length() > MAX_CLIPS_PER_REQUEST) {
            writeJson(output, 400, JSONObject().put("error", "素材元数据格式错误"))
            return
        }
        var accepted = 0
        val acceptedIds = mutableListOf<String>()
        runBlocking {
            for (index in 0 until clips.length()) {
                val clip = clips.optJSONObject(index) ?: continue
                val clientUuid = clip.optString("clientUuid").trim().take(80)
                val authorId = clip.optString("authorId").trim().take(MAX_AUTHOR_ID)
                if (clientUuid.isEmpty() || authorId != actorId) continue
                repository.upsertRemoteClipMetadata(
                    logId = logId,
                    clientUuid = clientUuid,
                    authorId = authorId,
                    dayEpoch = day,
                    durationMs = clip.optLong("durationMs").coerceAtLeast(0),
                    caption = clip.optString("caption").takeIf { it.isNotBlank() && it != "null" }?.take(500),
                    createdAt = clip.optLong("createdAt", System.currentTimeMillis()),
                    contentSha256 = clip.optString("contentSha256").takeIf { it.length == 64 },
                )
                accepted++
                acceptedIds += clientUuid
            }
        }
        writeJson(output, 200, JSONObject().put("accepted", accepted))
        acceptedIds.forEach { clientUuid ->
            thumbnailExecutor.execute {
                val clip = runBlocking { repository.getClipByClientUuid(logId, clientUuid) }
                val thumbnailMissing = clip?.localThumbPath.isNullOrBlank() || !File(clip?.localThumbPath.orEmpty()).isFile
                if (clip?.filePath.isNullOrBlank() && thumbnailMissing) {
                    val target = DailyLogStorage.remoteThumbnailFile(context, logId, clientUuid)
                    val endpoint = LanEndpoint(InetAddress.getLoopbackAddress(), port, "local-host", network = null)
                    if (LanHostClient(endpoint, inviteCode).downloadThumbnail(clientUuid, target)) {
                        runBlocking { repository.saveRemoteThumbnail(logId, clientUuid, target.absolutePath) }
                    }
                }
            }
        }
    }

    private fun registerSource(
        request: Request,
        actorId: String,
        remoteAddress: String,
        output: OutputStream,
    ) {
        val json = runCatching { JSONObject(String(request.body, StandardCharsets.UTF_8)) }.getOrNull()
        val claimedAuthorId = json?.optString("authorId")?.trim().orEmpty().take(MAX_AUTHOR_ID)
        val port = json?.optInt("port", -1) ?: -1
        if (claimedAuthorId != actorId || port !in 1024..65535) {
            writeJson(output, 400, JSONObject().put("error", "素材源信息错误"))
            return
        }
        val updated = runBlocking { repository.updateMemberSource(logId, actorId, remoteAddress, port) }
        if (!updated) {
            writeJson(output, 401, JSONObject().put("error", "请先加入 Log"))
            return
        }
        writeJson(output, 200, JSONObject().put("registered", true))
    }

    private fun heartbeat(
        request: Request,
        actorId: String,
        output: OutputStream,
    ) {
        val claimed =
            runCatching { JSONObject(String(request.body, StandardCharsets.UTF_8)).optString("authorId") }
                .getOrNull()
                ?.trim()
                .orEmpty()
                .take(MAX_AUTHOR_ID)
        if (claimed != actorId || !runBlocking { repository.touchLogMember(logId, actorId) }) {
            writeJson(output, 401, JSONObject().put("error", "设备尚未加入此 Log"))
            return
        }
        writeLog(output)
    }

    private fun receiveOperation(
        request: Request,
        authenticatedActorId: String,
        output: OutputStream,
    ) {
        val json = runCatching { JSONObject(String(request.body, StandardCharsets.UTF_8)) }.getOrNull()
        val actorId = json?.optString("actorId")?.trim().orEmpty().take(MAX_AUTHOR_ID)
        val operationId = json?.optString("operationId")?.trim().orEmpty().take(80)
        val entityType = json?.optString("entityType")?.trim().orEmpty().take(24)
        val operation = json?.optString("operation")?.trim().orEmpty().take(24)
        val payload = json?.optJSONObject("payload")
        if (actorId != authenticatedActorId || operationId.isEmpty() || payload == null || entityType !in setOf("clip", "comment")) {
            writeJson(output, 400, JSONObject().put("error", "同步操作格式错误"))
            return
        }
        val member = runBlocking { repository.logMembers(logId) }.firstOrNull { it.authorId == actorId }
        if (member == null) {
            writeJson(output, 401, JSONObject().put("error", "设备尚未加入此 Log"))
            return
        }
        if (entityType == "comment") {
            payload.put("authorId", actorId)
            payload.put("authorName", member.nickname)
        }
        val cursor =
            runCatching {
                runBlocking {
                    repository.acceptSyncOperation(logId, actorId, operationId, entityType, operation, payload.toString())
                }
            }.getOrElse {
                writeJson(output, 400, JSONObject().put("error", it.message ?: "操作无法应用"))
                return
            }
        writeJson(output, 200, JSONObject().put("accepted", true).put("cursor", cursor))
    }

    private fun authenticateMember(request: Request): String? {
        val authorId = request.headers["x-author-id"]?.trim()?.take(MAX_AUTHOR_ID)?.takeIf { it.isNotEmpty() } ?: return null
        val member = runBlocking { repository.logMembers(logId) }.firstOrNull { it.authorId == authorId } ?: return null
        return authorId.takeIf { verifySignature(request, member.publicKey) }
    }

    private fun verifySignature(
        request: Request,
        publicKey: String,
    ): Boolean {
        val timestamp = request.headers["x-request-time"]?.toLongOrNull() ?: return false
        if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > MAX_SIGNATURE_SKEW_MS) return false
        val signature = request.headers["x-request-signature"] ?: return false
        return DeviceIdentity.verifyRequest(publicKey, request.method, request.target, timestamp, request.body, signature)
    }

    private fun writeEvents(
        request: Request,
        output: OutputStream,
    ) {
        val after = queryParams(request.target)["after"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val events = runBlocking { repository.syncEventsAfter(logId, after) }
        val items =
            JSONArray().apply {
                events.forEach { event ->
                    put(
                        JSONObject()
                            .put("cursor", event.id)
                            .put("operationId", event.operationId)
                            .put("entityType", event.entityType)
                            .put("operation", event.operation)
                            .put("payload", JSONObject(event.payloadJson))
                            .put("createdAt", event.createdAt),
                    )
                }
            }
        writeJson(output, 200, JSONObject().put("cursor", events.lastOrNull()?.id ?: after).put("events", items))
    }

    private fun streamClip(
        request: Request,
        path: String,
        output: OutputStream,
    ) {
        val encodedId = path.removePrefix("/v1/clips/").removeSuffix("/video").trim('/')
        val clientUuid = URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name())
        val clip = runBlocking { repository.getClipByClientUuid(logId, clientUuid) }
        val file = clip?.takeIf { it.sourceKept && it.filePath.isNotBlank() }?.let { File(it.filePath) }
        if (file == null || !file.isFile || file.length() <= 0) {
            val source =
                clip?.authorId?.let { author ->
                    runBlocking { repository.logMembers(logId) }.firstOrNull { it.authorId == author }
                }
            if (source?.sourceAddress != null && source.sourcePort != null) {
                proxyPeerResource(request, clientUuid, "video", source.sourceAddress, source.sourcePort, output, Long.MAX_VALUE)
                return
            }
            writeJson(output, 404, JSONObject().put("error", "视频当前不在主机"))
            return
        }
        val parsed = HttpByteRange.parse(request.headers["range"], file.length())
        if (parsed is HttpByteRange.Unsatisfiable) {
            writeHeaders(
                output,
                416,
                mapOf("Content-Range" to "bytes */${file.length()}", "Content-Length" to "0"),
            )
            return
        }
        val range =
            when (parsed) {
                HttpByteRange.Full -> HttpByteRange.Partial(0, file.length() - 1)
                is HttpByteRange.Partial -> parsed
                HttpByteRange.Unsatisfiable -> return
            }
        val status = if (parsed is HttpByteRange.Partial) 206 else 200
        val headers =
            linkedMapOf(
                "Content-Type" to "video/mp4",
                "Accept-Ranges" to "bytes",
                "Content-Length" to range.length.toString(),
            )
        if (status == 206) headers["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/${file.length()}"
        writeHeaders(output, status, headers)
        if (request.method != "HEAD") {
            LanStreamRelay.copyFileRange(file, output, range)
            output.flush()
        }
    }

    /** 主机只转发响应头和固定缓冲的数据块，不知道也不保存完整视频内容。 */
    private fun streamThumbnail(
        request: Request,
        path: String,
        output: OutputStream,
    ) {
        val encodedId = path.removePrefix("/v1/clips/").removeSuffix("/thumb").trim('/')
        val clientUuid = URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name())
        val clip = runBlocking { repository.getClipByClientUuid(logId, clientUuid) }
        val cached = clip?.localThumbPath?.let(::File)?.takeIf { it.isFile && it.length() in 1..MAX_THUMB_BYTES }
        val localVideo = clip?.takeIf { it.sourceKept && it.filePath.isNotBlank() }?.let { File(it.filePath) }
        val file = cached ?: localVideo?.takeIf(File::isFile)?.let { runBlocking { ThumbnailStore.ensureThumbnailFile(context, it) } }
        if (file != null && file.isFile && file.length() in 1..MAX_THUMB_BYTES) {
            writeHeaders(output, 200, mapOf("Content-Type" to "image/jpeg", "Content-Length" to file.length().toString()))
            if (request.method != "HEAD") file.inputStream().use { LanStreamRelay.copyExactly(it, output, file.length()) }
            output.flush()
            return
        }
        val source =
            clip?.authorId?.let { author ->
                runBlocking { repository.logMembers(logId) }.firstOrNull { it.authorId == author }
            }
        if (source?.sourceAddress != null && source.sourcePort != null) {
            proxyPeerResource(request, clientUuid, "thumb", source.sourceAddress, source.sourcePort, output, MAX_THUMB_BYTES)
            return
        }
        writeJson(output, 404, JSONObject().put("error", "缩略图当前不可用"))
    }

    private fun proxyPeerResource(
        request: Request,
        clientUuid: String,
        resource: String,
        address: String,
        port: Int,
        downstream: OutputStream,
        maxLength: Long,
    ) {
        val upstream = Socket()
        var responseStarted = false
        try {
            upstream.connect(java.net.InetSocketAddress(address, port), HEADER_TIMEOUT_MS)
            upstream.soTimeout = HEADER_TIMEOUT_MS
            val requestText =
                buildString {
                    append("${request.method} ${LanPeerSourceServer.PATH_PREFIX}")
                    append(URLEncoder.encode(clientUuid, StandardCharsets.UTF_8.name()))
                    append("/$resource HTTP/1.1\r\n")
                    append("Host: $address:$port\r\n")
                    append("X-Invite-Code: $inviteCode\r\n")
                    request.headers["range"]?.let { append("Range: $it\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            upstream.getOutputStream().write(requestText.toByteArray(StandardCharsets.US_ASCII))
            upstream.getOutputStream().flush()

            val input = BufferedInputStream(upstream.getInputStream())
            val statusLine = readAsciiLine(input, MAX_HEADER_BYTES) ?: error("成员无响应")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("成员响应错误")
            val headers = linkedMapOf<String, String>()
            var consumed = statusLine.length
            while (true) {
                val line = readAsciiLine(input, MAX_HEADER_BYTES - consumed) ?: error("成员响应头不完整")
                consumed += line.length
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: error("成员未返回长度")
            if (length > maxLength) error("成员返回的资源过大")
            if (status != 200 && status != 206) {
                writeJson(downstream, 404, JSONObject().put("error", "成员资源当前不可用"))
                return
            }
            val forwarded = linkedMapOf("Content-Length" to length.toString())
            headers["content-type"]?.let { forwarded["Content-Type"] = it }
            headers["accept-ranges"]?.let { forwarded["Accept-Ranges"] = it }
            headers["content-range"]?.let { forwarded["Content-Range"] = it }
            writeHeaders(downstream, status, forwarded)
            responseStarted = true
            if (request.method != "HEAD") {
                LanStreamRelay.copyExactly(input, downstream, length)
                downstream.flush()
            }
        } catch (_: Exception) {
            if (!responseStarted) {
                runCatching { writeJson(downstream, 503, JSONObject().put("error", "成员设备已离线")) }
            }
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readAsciiLine(input, MAX_HEADER_BYTES) ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        var consumed = requestLine.length
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - consumed) ?: return null
            consumed += line.length
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return Request(parts[0].uppercase(Locale.US), parts[1], headers, body)
    }

    private fun readAsciiLine(
        input: BufferedInputStream,
        maxBytes: Int,
    ): String? {
        if (maxBytes <= 0) return null
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < maxBytes) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.write(next)
        }
        if (bytes.size() >= maxBytes) return null
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    private fun queryParams(target: String): Map<String, String> =
        target.substringAfter('?', "").split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val parts = pair.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.toMap()

    private fun writeJson(
        output: OutputStream,
        status: Int,
        json: JSONObject,
    ) {
        val body = json.toString().toByteArray(StandardCharsets.UTF_8)
        writeHeaders(
            output,
            status,
            mapOf("Content-Type" to "application/json; charset=utf-8", "Content-Length" to body.size.toString()),
        )
        output.write(body)
        output.flush()
    }

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        headers: Map<String, String>,
    ) {
        val reason = STATUS_REASONS[status] ?: "Error"
        val text =
            buildString {
                append("HTTP/1.1 $status $reason\r\n")
                headers.forEach { (key, value) -> append("$key: $value\r\n") }
                append("Connection: close\r\n\r\n")
            }
        output.write(text.toByteArray(StandardCharsets.US_ASCII))
    }

    override fun close() {
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
    }

    private data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    companion object {
        private const val MAX_CLIENTS = 8
        const val PREFERRED_PORT = 42731
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val MAX_AUTHOR_ID = 128
        private const val MAX_NICKNAME = 40
        private const val MAX_PUBLIC_KEY = 4096
        private const val MAX_CLIPS_PER_REQUEST = 500
        private const val MAX_SIGNATURE_SKEW_MS = 5 * 60_000L
        private const val MAX_THUMB_BYTES = 2L * 1024 * 1024
        private const val MEMBER_ONLINE_WINDOW_MS = 45_000L
        private val STATUS_REASONS =
            mapOf(200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 416 to "Range Not Satisfiable", 503 to "Service Unavailable")

        private fun isLanAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val bytes = address.address
            return address is Inet6Address && bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc
        }
    }
}
