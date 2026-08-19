package com.nierduolong.morningbell.dailylog.lan

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 局域网视频传输的唯一大文件 IO 切面。所有方法都使用固定缓冲，不创建 byte[] 大对象，
 * 也不在热点主机写临时副本；文件再大，转发内存峰值仍由 [BUFFER_BYTES] 决定。
 */
object LanStreamRelay {
    const val BUFFER_BYTES = 64 * 1024

    fun copyFileRange(
        source: File,
        output: OutputStream,
        range: HttpByteRange.Partial,
        bufferBytes: Int = BUFFER_BYTES,
    ): Long {
        require(bufferBytes in 1024..(1024 * 1024)) { "bufferBytes 超出安全范围" }
        require(range.start >= 0 && range.endInclusive < source.length()) { "range 越界" }
        RandomAccessFile(source, "r").use { input ->
            input.seek(range.start)
            val buffer = ByteArray(bufferBytes)
            var remaining = range.length
            var copied = 0L
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                copied += read
                remaining -= read
            }
            return copied
        }
    }

    /** 从另一台成员设备转发响应体时使用；不会在主机落盘。 */
    fun copyExactly(
        input: InputStream,
        output: OutputStream,
        byteCount: Long,
        bufferBytes: Int = BUFFER_BYTES,
    ): Long {
        require(byteCount >= 0)
        require(bufferBytes in 1024..(1024 * 1024)) { "bufferBytes 超出安全范围" }
        val buffer = ByteArray(bufferBytes)
        var remaining = byteCount
        var copied = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
            copied += read
        }
        return copied
    }

    fun sha256(
        file: File,
        bufferBytes: Int = BUFFER_BYTES,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(bufferBytes).use { input ->
            val buffer = ByteArray(bufferBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
