package org.kaloscope.tv.core.player

class PlaybackProgressRecorder(
    private val intervalMillis: Long = 15_000,
) {
    private var lastAttemptAtMillis: Long? = null
    private var lastAttemptId = 0L
    private var lastPositionSeconds: Long? = null
    private var lastPercentage: Int? = null

    fun beginRecord(
        positionMillis: Long,
        durationMillis: Long,
        nowMillis: Long,
        reason: ProgressReason,
    ): Long? {
        if (positionMillis < 0) {
            return null
        }
        // HLS duration may remain unknown while the current position is already valid.
        val safePosition = if (durationMillis > 0) {
            positionMillis.coerceAtMost(durationMillis)
        } else {
            positionMillis
        }
        val positionSeconds = safePosition / 1_000
        val percentage = durationMillis
            .takeIf { it > 0 }
            ?.let { ((safePosition * 100) / it).toInt().coerceIn(0, 100) }
        val changed = positionSeconds != lastPositionSeconds || percentage != lastPercentage
        if (!changed) {
            return null
        }
        val intervalElapsed =
            lastAttemptAtMillis?.let { nowMillis - it >= intervalMillis } ?: true
        // Lifecycle events bypass throttling, but unchanged progress still stays deduplicated.
        if (reason == ProgressReason.Periodic && !intervalElapsed) {
            return null
        }
        lastAttemptAtMillis = nowMillis
        lastPositionSeconds = positionSeconds
        lastPercentage = percentage
        lastAttemptId += 1
        return lastAttemptId
    }

    fun recordFailed(attemptId: Long) {
        // An older failure must not invalidate progress already queued after it.
        if (attemptId != lastAttemptId) {
            return
        }
        // Keep the attempt time so periodic retries still respect the interval.
        lastPositionSeconds = null
        lastPercentage = null
    }
}

enum class ProgressReason {
    Started,
    Periodic,
    Paused,
    Seeked,
    ItemChanged,
    Exit,
    Error,
}
