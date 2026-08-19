package com.nierduolong.morningbell.dailylog.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 不造 1 GiB byte[]，而是让虚拟输入流实时生成数据。这既能压并发传输循环，也能证明测试
 * 本身和正式实现一样不会把文件大小转化成内存占用。
 */
class LanStreamingStressTest {
    @Test
    fun relaysOneGiBAcrossEightClientsWithOnlyFixedChunks() {
        val clients = 8
        val bytesPerClient = 128L * 1024 * 1024
        val pool = Executors.newFixedThreadPool(clients)
        try {
            val futures =
                (0 until clients).map { marker ->
                    pool.submit(
                        Callable {
                            val input = GeneratedInputStream(bytesPerClient, marker.toByte())
                            val output = VerifyingSink(marker.toByte())
                            val copied = LanStreamRelay.copyExactly(input, output, bytesPerClient)
                            assertEquals(bytesPerClient, copied)
                            assertEquals(bytesPerClient, output.count)
                            assertTrue(input.largestRequestedRead <= LanStreamRelay.BUFFER_BYTES)
                            assertTrue(output.largestWrite <= LanStreamRelay.BUFFER_BYTES)
                            copied
                        },
                    )
                }
            assertEquals(1024L * 1024 * 1024, futures.sumOf { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun survivesTenThousandAbruptDisconnectsWithoutOverRead() {
        val random = Random(20260811L)
        repeat(10_000) {
            val advertised = random.nextInt(256 * 1024).toLong() + 1
            val available = random.nextInt(advertised.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong()
            val input = GeneratedInputStream(available, 7)
            val sink = VerifyingSink(7)
            val copied = LanStreamRelay.copyExactly(input, sink, advertised)
            assertEquals(available, copied)
            assertEquals(available, sink.count)
            assertTrue(input.largestRequestedRead <= LanStreamRelay.BUFFER_BYTES)
        }
    }

    @Test
    fun fuzzesOneHundredThousandByteRanges() {
        val random = Random(42L)
        repeat(100_000) {
            val size = random.nextInt(16 * 1024 * 1024).toLong() + 1
            val start = random.nextLong().ushr(1) % (size * 2)
            val requestedEnd = start + (random.nextLong().ushr(1) % (size * 2))
            val parsed = HttpByteRange.parse("bytes=$start-$requestedEnd", size)
            if (start >= size) {
                assertTrue(parsed === HttpByteRange.Unsatisfiable)
            } else {
                val partial = parsed as HttpByteRange.Partial
                assertEquals(start, partial.start)
                assertEquals(minOf(requestedEnd, size - 1), partial.endInclusive)
                assertTrue(partial.length in 1..size)
            }
        }
    }

    @Test
    fun retryBackoffIsMonotonicAndCappedUnderHeavyFailure() {
        var previous = 0L
        repeat(100_000) { attempts ->
            val delay = SyncRetryPolicy.nextDelayMs(attempts)
            assertTrue(delay >= previous)
            assertTrue(delay <= SyncRetryPolicy.MAX_DELAY_MS)
            previous = delay
        }
        assertEquals(SyncRetryPolicy.MAX_DELAY_MS, previous)
    }

    private class GeneratedInputStream(
        private val length: Long,
        private val marker: Byte,
    ) : InputStream() {
        private var position = 0L
        var largestRequestedRead = 0
            private set

        override fun read(): Int =
            if (position >= length) -1 else {
                position++
                marker.toInt() and 0xff
            }

        override fun read(buffer: ByteArray, offset: Int, requested: Int): Int {
            largestRequestedRead = maxOf(largestRequestedRead, requested)
            if (position >= length) return -1
            val count = minOf(requested.toLong(), length - position).toInt()
            Arrays.fill(buffer, offset, offset + count, marker)
            position += count
            return count
        }
    }

    private class VerifyingSink(private val marker: Byte) : OutputStream() {
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
