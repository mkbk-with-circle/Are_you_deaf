package com.nierduolong.morningbell.dailylog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailDecodePolicyTest {
    @Test
    fun estimatesArgbMemoryInKiB() {
        assertEquals(710, ThumbnailDecodePolicy.estimatedKiB(320, 568))
        assertTrue(ThumbnailDecodePolicy.estimatedKiB(Int.MAX_VALUE, Int.MAX_VALUE) > 0)
    }

    @Test
    fun downsamplesDimensionBombButKeepsNormalThumbnail() {
        assertEquals(1, ThumbnailDecodePolicy.sampleSize(320, 640, 512 * 1024))
        assertEquals(16, ThumbnailDecodePolicy.sampleSize(8_000, 8_000, 512 * 1024))
    }
}
