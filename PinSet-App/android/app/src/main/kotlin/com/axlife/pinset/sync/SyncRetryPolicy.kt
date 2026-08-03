package com.axlife.pinset.sync

object SyncRetryPolicy {
    private val delaysMs = longArrayOf(
        60_000L,
        5 * 60_000L,
        15 * 60_000L,
        60 * 60_000L,
        6 * 60 * 60_000L
    )

    fun delayMs(attemptCount: Int): Long =
        delaysMs[attemptCount.coerceIn(0, delaysMs.lastIndex)]
}
