@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nierduolong.morningbell.dailylog.lan

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class LanVideoReference(
    val uri: Uri,
    val endpoint: LanEndpoint,
    val inviteCode: String,
    val clientUuid: String,
)

/**
 * ExoPlayer 的私网 DataSource。它用原始 socket 绑定 NSD 返回的 Wi-Fi Network，不依赖公网 HTTP
 * 栈，也不会因系统默认网络是蜂窝网而把请求回落到运营商链路。
 */
class LanVideoDataSource(
    private val references: Map<Uri, LanVideoReference>,
) : BaseDataSource(true) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var opened = false
    private var remaining = 0L
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val reference = references[dataSpec.uri] ?: throw IOException("附近视频引用已失效")
        val connection =
            reference.endpoint.network?.socketFactory?.createSocket(reference.endpoint.host, reference.endpoint.port)
                ?: Socket().apply {
                    connect(InetSocketAddress(reference.endpoint.host, reference.endpoint.port), TIMEOUT_MS)
                }
        socket = connection
        connection.soTimeout = TIMEOUT_MS
        val requestedEnd =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
        val path = "/v1/clips/${URLEncoder.encode(reference.clientUuid, "UTF-8")}/video"
        val request =
            buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: ${reference.endpoint.host.hostAddress}:${reference.endpoint.port}\r\n")
                append("X-Invite-Code: ${reference.inviteCode}\r\n")
                append("Range: bytes=${dataSpec.position}-$requestedEnd\r\n")
                append("Connection: close\r\n\r\n")
            }
        connection.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        connection.getOutputStream().flush()

        val stream = BufferedInputStream(connection.getInputStream(), LanStreamRelay.BUFFER_BYTES)
        val statusLine = readLine(stream) ?: throw IOException("附近视频源没有响应")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("附近视频响应错误")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(stream) ?: throw IOException("附近视频响应头不完整")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        if (status != 200 && status != 206) throw IOException("附近视频当前不可用（$status）")
        remaining = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("附近视频缺少长度")
        input = stream
        currentUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = input?.read(buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: C.RESULT_END_OF_INPUT
        if (count < 0) return C.RESULT_END_OF_INPUT
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        runCatching { input?.close() }
        input = null
        runCatching { socket?.close() }
        socket = null
        remaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun readLine(stream: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = stream.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    class Factory(private val references: Map<Uri, LanVideoReference>) : DataSource.Factory {
        override fun createDataSource(): DataSource = LanVideoDataSource(references)
    }

    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16 * 1024
    }
}
