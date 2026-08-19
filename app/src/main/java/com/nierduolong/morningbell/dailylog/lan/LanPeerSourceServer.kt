package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.dailylog.ThumbnailStore
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 成员手机的只读素材源。主机需要视频时从这里按 Range 读取，成员无需先上传完整文件。 */
class LanPeerSourceServer(
    private val context: Context,
    private val repository: AppRepository,
    private val logId: Long,
    private val inviteCode: String,
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientsExecutor = Executors.newFixedThreadPool(4)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int get() = server.localPort

    fun start() {
        acceptExecutor.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!isLanAddress(socket.inetAddress) || sockets.size >= 4) {
                    runCatching { socket.close() }
                    continue
                }
                sockets += socket
                clientsExecutor.execute {
                    try {
                        handle(socket)
                    } finally {
                        sockets -= socket
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        val output = socket.getOutputStream()
        if (headers["x-invite-code"] != inviteCode) {
            writeEmpty(output, 401)
            return
        }
        if ((method != "GET" && method != "HEAD") || !path.startsWith(PATH_PREFIX)) {
            writeEmpty(output, 404)
            return
        }
        val resource = when {
            path.endsWith("/video") -> "video"
            path.endsWith("/thumb") -> "thumb"
            else -> {
                writeEmpty(output, 404)
                return
            }
        }
        val encodedId = path.removePrefix(PATH_PREFIX).removeSuffix("/$resource").trim('/')
        val clientUuid = URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name())
        val clip = runBlocking { repository.getClipByClientUuid(logId, clientUuid) }
        val videoFile = clip?.takeIf { it.sourceKept && it.filePath.isNotBlank() }?.let { File(it.filePath) }
        val file =
            if (resource == "thumb" && videoFile?.isFile == true) {
                runBlocking { ThumbnailStore.ensureThumbnailFile(context, videoFile) }
            } else {
                videoFile
            }
        if (file == null || !file.isFile || file.length() <= 0) {
            writeEmpty(output, 404)
            return
        }
        val parsed = HttpByteRange.parse(headers["range"], file.length())
        if (parsed is HttpByteRange.Unsatisfiable) {
            writeHeaders(output, 416, mapOf("Content-Range" to "bytes */${file.length()}", "Content-Length" to "0"))
            return
        }
        val range =
            if (parsed is HttpByteRange.Partial) parsed else HttpByteRange.Partial(0, file.length() - 1)
        val status = if (parsed is HttpByteRange.Partial) 206 else 200
        val responseHeaders =
            linkedMapOf(
                "Content-Type" to if (resource == "thumb") "image/jpeg" else "video/mp4",
                "Accept-Ranges" to "bytes",
                "Content-Length" to range.length.toString(),
            )
        if (status == 206) responseHeaders["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/${file.length()}"
        writeHeaders(output, status, responseHeaders)
        if (method != "HEAD") {
            LanStreamRelay.copyFileRange(file, output, range)
            output.flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    private fun writeEmpty(
        output: OutputStream,
        status: Int,
    ) = writeHeaders(output, status, mapOf("Content-Length" to "0"))

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        headers: Map<String, String>,
    ) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            else -> "Error"
        }
        val value =
            buildString {
                append("HTTP/1.1 $status $reason\r\n")
                headers.forEach { (key, item) -> append("$key: $item\r\n") }
                append("Connection: close\r\n\r\n")
            }
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        acceptExecutor.shutdownNow()
        clientsExecutor.shutdownNow()
    }

    companion object {
        const val PATH_PREFIX = "/v1/source/clips/"

        private fun isLanAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val bytes = address.address
            return address is Inet6Address && bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc
        }
    }
}
