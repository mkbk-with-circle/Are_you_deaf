package com.nierduolong.morningbell.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferProtocolTest {
    @Test
    fun rejectsTraversalAndTokenPrefixConfusion() {
        assertNull(TransferPathPolicy.split("../DCIM/private.jpg"))
        assertNull(TransferPathPolicy.split("DCIM/../private.jpg"))
        assertNull(TransferPathPolicy.split("DCIM/./private.jpg"))
        assertNull(TransferPathPolicy.split("DCIM/evil\u0000.jpg"))
        assertNotNull(TransferPathPolicy.split("DCIM/100CANON/IMG_0001.JPG"))

        val prefix = "/s/abcdefghijklmnopqrstuvwx"
        assertTrue(TransferPathPolicy.authorizedRoute(prefix, prefix))
        assertTrue(TransferPathPolicy.authorizedRoute("$prefix/file/DCIM/a.jpg", prefix))
        assertFalse(TransferPathPolicy.authorizedRoute("${prefix}evil/file/a.jpg", prefix))
        assertFalse(TransferPathPolicy.authorizedRoute("/s/other", prefix))
    }

    @Test
    fun createsHighEntropyUrlSafeSessionTokens() {
        val tokens = HashSet<String>()
        repeat(10_000) {
            val token = TransferNetworkUtils.newAccessToken()
            assertTrue(token.length >= 32)
            assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertTrue(tokens.add(token))
        }
    }

    @Test
    fun streamsAcrossFourClientsWithFixedMemory() {
        val clients = 4
        val bytes = 32L * 1024 * 1024
        val pool = Executors.newFixedThreadPool(clients)
        try {
            val futures = (0 until clients).map { marker ->
                pool.submit(Callable {
                    val input = GeneratedInput(bytes, marker.toByte())
                    val output = VerifyingOutput(marker.toByte())
                    assertEquals(bytes, TransferStreamRelay.copyExactly(input, output, bytes))
                    assertEquals(bytes, output.count)
                    assertTrue(input.largestRequest <= TransferStreamRelay.BUFFER_BYTES)
                    assertTrue(output.largestWrite <= TransferStreamRelay.BUFFER_BYTES)
                    bytes
                })
            }
            assertEquals(bytes * clients, futures.sumOf { it.get(20, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun survivesTenThousandInterruptedReadsWithoutOverAllocation() {
        val random = Random(20260819L)
        repeat(10_000) { index ->
            val advertised = random.nextInt(512 * 1024).toLong() + 1
            val available = random.nextInt(advertised.toInt() + 1).toLong()
            val input = GeneratedInput(available, index.toByte())
            val output = VerifyingOutput(index.toByte())
            assertEquals(available, TransferStreamRelay.copyExactly(input, output, advertised))
            assertEquals(available, output.count)
            assertTrue(input.largestRequest <= TransferStreamRelay.BUFFER_BYTES)
        }
    }

    @Test
    fun classifiesCameraAndMediaFormats() {
        assertEquals("raw", TransferMime.kindFor("IMG_0001.CR3"))
        assertEquals("image", TransferMime.kindFor("IMG_0002.HEIC", "image/heic"))
        assertEquals("video", TransferMime.kindFor("C0003.MP4"))
        assertEquals("audio", TransferMime.kindFor("field.wav"))
        assertEquals("application/pdf", TransferMime.typeFor("notes.pdf"))
    }

    private class GeneratedInput(private val total: Long, private val marker: Byte) : InputStream() {
        private var position = 0L
        var largestRequest = 0
            private set

        override fun read(): Int = if (position >= total) -1 else (marker.toInt() and 0xff).also { position++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            largestRequest = maxOf(largestRequest, length)
            if (position >= total) return -1
            val count = minOf(length.toLong(), total - position).toInt()
            Arrays.fill(buffer, offset, offset + count, marker)
            position += count
            return count
        }
    }

    private class VerifyingOutput(private val marker: Byte) : OutputStream() {
        var count = 0L
            private set
        var largestWrite = 0
            private set

        override fun write(value: Int) {
            assertEquals(marker.toInt() and 0xff, value)
            count++
            largestWrite = maxOf(largestWrite, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > 0) {
                assertEquals(marker, buffer[offset])
                assertEquals(marker, buffer[offset + length - 1])
            }
            count += length
            largestWrite = maxOf(largestWrite, length)
        }
    }
}
