package com.nierduolong.morningbell.transfer

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NearbyFileShareServerTest {
    @Test
    fun protectsListingAndServesResumableRanges() {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val catalog = MemoryCatalog(bytes)
        val token = "abcdefghijklmnopqrstuvwxyz123456"
        val server = NearbyFileShareServer(
            InstrumentationRegistry.getInstrumentation().targetContext,
            catalog,
            token,
            NoopListener,
        )
        server.start()
        try {
            val wrong = request(server.port, "GET /s/wrong/_api/list HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(404, wrong.status)

            val listing = request(server.port, "GET /s/$token/_api/list HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(200, listing.status)
            assertTrue(String(listing.body).contains("camera.bin"))

            val range = request(
                server.port,
                "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=12345-13344\r\n\r\n",
            )
            assertEquals(206, range.status)
            assertEquals(1000, range.body.size)
            assertTrue(range.body.contentEquals(bytes.copyOfRange(12345, 13345)))

            val suffix = request(
                server.port,
                "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=-257\r\n\r\n",
            )
            assertEquals(206, suffix.status)
            assertTrue(suffix.body.contentEquals(bytes.takeLast(257).toByteArray()))

            val invalid = request(
                server.port,
                "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=${bytes.size + 10}-\r\n\r\n",
            )
            assertEquals(416, invalid.status)

            // 模拟下载到 200 KiB 时断网，再用第二个 Range 接上；拼接结果必须与原文件一致。
            val firstPart = request(
                server.port,
                "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-204799\r\n\r\n",
            )
            val secondPart = request(
                server.port,
                "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=204800-\r\n\r\n",
            )
            assertEquals(206, firstPart.status)
            assertEquals(206, secondPart.status)
            assertTrue((firstPart.body + secondPart.body).contentEquals(bytes))

            val staleIfRange = request(
                server.port,
                "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=204800-\r\nIf-Range: W/\"stale\"\r\n\r\n",
            )
            assertEquals(200, staleIfRange.status)
            assertTrue(staleIfRange.body.contentEquals(bytes))
        } finally {
            server.close()
        }
    }

    @Test
    fun handlesSixBrowserConnectionsWithTwoCardReadersWithoutCorruption() {
        val bytes = ByteArray(8 * 1024 * 1024) { (it % 239).toByte() }
        val token = "abcdefghijklmnopqrstuvwxyz123456"
        val server = NearbyFileShareServer(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MemoryCatalog(bytes, removable = true),
            token,
            NoopListener,
        )
        server.start()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 6).map { index ->
                pool.submit(Callable {
                    val start = index * 1024 * 1024
                    val end = start + 1024 * 1024 - 1
                    val response = request(
                        server.port,
                        "GET /s/$token/file/camera.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=$start-$end\r\n\r\n",
                    )
                    assertEquals(206, response.status)
                    assertTrue(response.body.contentEquals(bytes.copyOfRange(start, end + 1)))
                    response.body.size
                })
            }
            assertEquals(6 * 1024 * 1024, tasks.sumOf { it.get(20, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    private fun request(port: Int, request: String): Response {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readLine(input)
            val status = statusLine.split(' ')[1].toInt()
            var contentLength: Int? = null
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toInt()
                }
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var remaining = contentLength
            while (remaining == null || remaining > 0) {
                val read = input.read(buffer, 0, remaining?.let { minOf(it, buffer.size) } ?: buffer.size)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (remaining != null) remaining -= read
            }
            return Response(status, output.toByteArray())
        }
    }

    private fun readLine(input: BufferedInputStream): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        return output.toString(StandardCharsets.ISO_8859_1.name())
    }

    private data class Response(val status: Int, val body: ByteArray)

    private class MemoryCatalog(
        private val bytes: ByteArray,
        override val removable: Boolean = false,
    ) : TransferSourceCatalog {
        override val label = "memory"
        override fun isReadable() = true
        override fun list(relativePath: String) = if (relativePath.isBlank()) {
            listOf(TransferListItem("camera.bin", "camera.bin", bytes.size.toLong(), 1L, "application/octet-stream", "file"))
        } else {
            emptyList()
        }

        override fun resolveFile(relativePath: String): TransferReadableEntry? =
            if (relativePath != "camera.bin") null else TransferReadableEntry(
                path = "camera.bin",
                size = bytes.size.toLong(),
                lastModified = 1L,
                mimeType = "application/octet-stream",
                etagSeed = "memory-${bytes.size}",
                openAt = { offset -> ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) },
            )
    }

    private object NoopListener : NearbyFileShareServer.Listener {
        override fun onMetrics(activeClients: Int, transferredBytes: Long) = Unit
        override fun onSourceUnavailable(message: String) = Unit
    }
}
