package com.nierduolong.morningbell.dailylog.lan

/** RFC 7233 单区间子集。视频播放器只需要单 Range；拒绝多区间可避免在内存拼 multipart。 */
sealed interface HttpByteRange {
    data object Full : HttpByteRange

    data class Partial(
        val start: Long,
        val endInclusive: Long,
    ) : HttpByteRange {
        val length: Long get() = endInclusive - start + 1
    }

    data object Unsatisfiable : HttpByteRange

    companion object {
        fun parse(
            header: String?,
            totalLength: Long,
        ): HttpByteRange {
            if (header.isNullOrBlank()) return Full
            if (totalLength <= 0L) return Unsatisfiable
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return Unsatisfiable
            val spec = value.substringAfter('=').trim()
            if (spec.isEmpty() || ',' in spec) return Unsatisfiable

            val dash = spec.indexOf('-')
            if (dash < 0) return Unsatisfiable
            val left = spec.substring(0, dash).trim()
            val right = spec.substring(dash + 1).trim()

            if (left.isEmpty()) {
                val suffixLength = right.toLongOrNull() ?: return Unsatisfiable
                if (suffixLength <= 0) return Unsatisfiable
                val actual = suffixLength.coerceAtMost(totalLength)
                return Partial(totalLength - actual, totalLength - 1)
            }

            val start = left.toLongOrNull() ?: return Unsatisfiable
            if (start < 0 || start >= totalLength) return Unsatisfiable
            val requestedEnd = if (right.isEmpty()) totalLength - 1 else right.toLongOrNull() ?: return Unsatisfiable
            if (requestedEnd < start) return Unsatisfiable
            return Partial(start, requestedEnd.coerceAtMost(totalLength - 1))
        }
    }
}
