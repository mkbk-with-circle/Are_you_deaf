package com.nierduolong.morningbell.dailylog.lan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanHostClientAvailabilityTest {
    @Test
    fun checksOnlyHeadersAndAcceptsPositiveVideoLength() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val requestFuture =
                executor.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        val lines = buildList {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                add(line)
                            }
                        }
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 12345\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                        lines.joinToString("\n")
                    }
                }
            val endpoint = LanEndpoint(InetAddress.getLoopbackAddress(), server.localPort, "test", network = null)

            assertTrue(LanHostClient(endpoint, "123456").videoAvailable("clip with space"))
            val request = requestFuture.get(2, TimeUnit.SECONDS)
            assertTrue(request.startsWith("HEAD /v1/clips/clip+with+space/video HTTP/1.1"))
            assertTrue("X-Invite-Code: 123456" in request)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsMissingOrZeroLengthVideo() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    var matched = 0
                    while (matched < 4) {
                        matched = when (input.read()) {
                            '\r'.code -> if (matched == 0 || matched == 2) matched + 1 else 0
                            '\n'.code -> if (matched == 1 || matched == 3) matched + 1 else 0
                            else -> 0
                        }
                    }
                    socket.getOutputStream().write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.US_ASCII),
                    )
                }
            }
            val endpoint = LanEndpoint(InetAddress.getLoopbackAddress(), server.localPort, "test", network = null)
            assertFalse(LanHostClient(endpoint, "123456").videoAvailable("missing"))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun stressChecksFiveHundredSourcesWithFixedWorkerPools() {
        val probes = 500
        val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        val acceptor = Executors.newSingleThreadExecutor()
        val serverWorkers = Executors.newFixedThreadPool(8)
        val clients = Executors.newFixedThreadPool(8)
        try {
            val accepted =
                acceptor.submit {
                    repeat(probes) {
                        val socket = server.accept()
                        serverWorkers.submit {
                            socket.use {
                                val input = it.getInputStream()
                                var matched = 0
                                while (matched < 4) {
                                    matched = when (input.read()) {
                                        '\r'.code -> if (matched == 0 || matched == 2) matched + 1 else 0
                                        '\n'.code -> if (matched == 1 || matched == 3) matched + 1 else 0
                                        -1 -> break
                                        else -> 0
                                    }
                                }
                                it.getOutputStream().write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n"
                                        .toByteArray(StandardCharsets.US_ASCII),
                                )
                            }
                        }
                    }
                }
            val endpoint = LanEndpoint(InetAddress.getLoopbackAddress(), server.localPort, "stress", network = null)
            val results =
                clients.invokeAll(
                    (0 until probes).map { index ->
                        Callable { LanHostClient(endpoint, "123456").videoAvailable("clip-$index") }
                    },
                )

            assertTrue(results.all { it.get(5, TimeUnit.SECONDS) })
            accepted.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            acceptor.shutdownNow()
            serverWorkers.shutdownNow()
            clients.shutdownNow()
        }
    }
}
