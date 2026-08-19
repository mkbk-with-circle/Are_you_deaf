package com.nierduolong.morningbell.dailylog.lan

/** 断连后的指数退避；有上限，因此不会高频打满热点，也不会无限延长到无法恢复。 */
object SyncRetryPolicy {
    const val MAX_DELAY_MS = 5 * 60_000L

    fun nextDelayMs(failedAttempts: Int): Long =
        (2_000L shl failedAttempts.coerceIn(0, 20)).coerceAtMost(MAX_DELAY_MS)
}
