package eu.kanade.tachiyomi.ui.player.helper

import java.util.Locale

/**
 * Pure helper for encoding and decoding video chapter progress into the existing
 * integer `last_page_read` column without schema changes.
 *
 * Encoding format: `trackIndex * 1_000_000 + positionSeconds`
 */
object VideoProgressHelper {
    const val MULTIPLIER = 1_000_000L

    /**
     * Encodes track index and position in seconds into a single integer.
     */
    fun encode(trackIndex: Int, positionSeconds: Long): Int {
        val safeIndex = trackIndex.coerceAtLeast(0)
        val safeSeconds = positionSeconds.coerceAtLeast(0)
        val result = safeIndex * MULTIPLIER + safeSeconds
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Decodes the encoded integer into (trackIndex, positionSeconds).
     */
    fun decode(encodedValue: Long): Pair<Int, Long> {
        if (encodedValue <= 0) return Pair(0, 0L)
        val trackIndex = (encodedValue / MULTIPLIER).toInt()
        val positionSeconds = encodedValue % MULTIPLIER
        return Pair(trackIndex, positionSeconds)
    }

    /**
     * Calculates the resume position: saved position minus 3 seconds (floored at 0).
     */
    fun calculateResumePosition(savedPositionSeconds: Long): Long {
        return (savedPositionSeconds - 3L).coerceAtLeast(0L)
    }

    /**
     * Checks if playback has crossed the 90% threshold for marking the chapter as read.
     */
    fun isOverReadThreshold(currentPositionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        return currentPositionMs >= (durationMs * 0.9)
    }

    /**
     * Formats seconds into a human-readable "mm:ss" or "hh:mm:ss" string.
     */
    fun formatDuration(seconds: Long): String {
        val totalSec = seconds.coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
        }
    }
}
