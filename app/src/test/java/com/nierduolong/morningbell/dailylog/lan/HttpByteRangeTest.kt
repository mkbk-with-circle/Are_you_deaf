package com.nierduolong.morningbell.dailylog.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class HttpByteRangeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesClosedOpenAndSuffixRanges() {
        assertEquals(HttpByteRange.Partial(10, 19), HttpByteRange.parse("bytes=10-19", 100))
        assertEquals(HttpByteRange.Partial(90, 99), HttpByteRange.parse("bytes=90-", 100))
        assertEquals(HttpByteRange.Partial(75, 99), HttpByteRange.parse("bytes=-25", 100))
    }

    @Test
    fun clampsEndAndRejectsInvalidRanges() {
        assertEquals(HttpByteRange.Partial(95, 99), HttpByteRange.parse("bytes=95-999", 100))
        assertSame(HttpByteRange.Unsatisfiable, HttpByteRange.parse("bytes=100-", 100))
        assertSame(HttpByteRange.Unsatisfiable, HttpByteRange.parse("bytes=20-10", 100))
        assertSame(HttpByteRange.Unsatisfiable, HttpByteRange.parse("bytes=0-1,4-5", 100))
    }

    @Test
    fun copiesOnlyRequestedBytesWithFixedBuffer() {
        val source = temp.newFile("source.bin")
        source.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        val output = ByteArrayOutputStream()

        val copied =
            LanStreamRelay.copyFileRange(
                source,
                output,
                HttpByteRange.Partial(777, 1776),
                bufferBytes = 1024,
            )

        assertEquals(1000L, copied)
        assertArrayEquals(source.readBytes().copyOfRange(777, 1777), output.toByteArray())
    }
}
