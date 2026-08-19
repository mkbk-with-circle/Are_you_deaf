package com.nierduolong.morningbell.transfer

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object TransferPathPolicy {
    fun split(relativePath: String): List<String>? {
        if (relativePath.isBlank()) return emptyList()
        if (relativePath.length > MAX_PATH_CHARS || '\u0000' in relativePath) return null
        val parts = relativePath.trim('/').split('/').filter(String::isNotEmpty)
        if (parts.size > MAX_DEPTH || parts.any { it == "." || it == ".." }) return null
        return parts
    }

    fun authorizedRoute(path: String, expectedPrefix: String): Boolean {
        if (path.length < expectedPrefix.length) return false
        val prefix = path.take(expectedPrefix.length)
        return MessageDigest.isEqual(prefix.toByteArray(), expectedPrefix.toByteArray()) &&
            (path.length == expectedPrefix.length || path[expectedPrefix.length] == '/')
    }

    private const val MAX_PATH_CHARS = 4_096
    private const val MAX_DEPTH = 24
}

object TransferStreamRelay {
    const val BUFFER_BYTES = 128 * 1024

    fun copyExactly(
        input: InputStream,
        output: OutputStream,
        byteCount: Long,
        onBytes: (Int) -> Unit = {},
    ): Long {
        require(byteCount >= 0)
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = byteCount
        var copied = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            remaining -= read
            onBytes(read)
        }
        return copied
    }

    fun copyUntilEnd(
        input: InputStream,
        output: OutputStream,
        onBytes: (Int) -> Unit = {},
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            onBytes(read)
        }
        return copied
    }
}
