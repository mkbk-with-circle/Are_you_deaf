package com.nierduolong.morningbell.dailylog

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageFileClassifierTest {
    @Test
    fun classifiesCurrentAndLegacyStoragePaths() {
        assertEquals(StorageFileCategory.ORIGINAL, StorageFileClassifier.category("logs/7/clips/2026-08-12/clip.mp4"))
        assertEquals(StorageFileCategory.COMPILATION, StorageFileClassifier.category("logs/7/compilations/daily.mp4"))
        assertEquals(StorageFileCategory.COMPILATION, StorageFileClassifier.category("compilations/daily.mp4"))
        assertEquals(StorageFileCategory.THUMBNAIL, StorageFileClassifier.category("logs/7/thumbs/a.jpg"))
        assertEquals(StorageFileCategory.THUMBNAIL, StorageFileClassifier.category("thumbs/cache.jpg"))
        assertEquals(StorageFileCategory.OTHER, StorageFileClassifier.category("unknown/keep.bin"))
    }

    @Test
    fun temporarySuffixWinsOverItsContainingDirectory() {
        assertEquals(StorageFileCategory.TEMPORARY, StorageFileClassifier.category("logs/7/compilations/daily.mp4.tmp"))
        assertEquals(StorageFileCategory.TEMPORARY, StorageFileClassifier.category("logs/7/thumbs/a.jpg.part-42"))
        assertEquals(StorageFileCategory.TEMPORARY, StorageFileClassifier.category("logs\\7\\clips\\video.part"))
    }
}
