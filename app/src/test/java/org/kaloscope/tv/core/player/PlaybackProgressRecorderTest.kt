package org.kaloscope.tv.core.player

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackProgressRecorderTest {
    @Test
    fun `periodic progress records at most once per interval`() {
        val recorder = PlaybackProgressRecorder(intervalMillis = 15_000)

        assertNotNull(recorder.beginRecord(1_000, 60_000, 0, ProgressReason.Started))
        assertNull(recorder.beginRecord(5_000, 60_000, 5_000, ProgressReason.Periodic))
        assertNotNull(recorder.beginRecord(16_000, 60_000, 15_000, ProgressReason.Periodic))
    }

    @Test
    fun `lifecycle events record changed progress immediately`() {
        val recorder = PlaybackProgressRecorder(intervalMillis = 15_000)
        recorder.beginRecord(1_000, 60_000, 0, ProgressReason.Started)

        assertNotNull(recorder.beginRecord(2_000, 60_000, 1_000, ProgressReason.Paused))
        assertNull(recorder.beginRecord(2_000, 60_000, 2_000, ProgressReason.Exit))
    }

    @Test
    fun `unknown duration still records changed position`() {
        val recorder = PlaybackProgressRecorder()

        assertNotNull(recorder.beginRecord(10_000, -1, 20_000, ProgressReason.Exit))
    }

    @Test
    fun `failed save can retry unchanged progress on a lifecycle event`() {
        val recorder = PlaybackProgressRecorder()
        val attemptId = checkNotNull(
            recorder.beginRecord(10_000, 60_000, 0, ProgressReason.Paused),
        )

        recorder.recordFailed(attemptId)

        assertNotNull(recorder.beginRecord(10_000, 60_000, 1_000, ProgressReason.Exit))
        assertNull(recorder.beginRecord(10_000, 60_000, 2_000, ProgressReason.Exit))
    }

    @Test
    fun `failed periodic save keeps the retry interval`() {
        val recorder = PlaybackProgressRecorder(intervalMillis = 15_000)
        val attemptId = checkNotNull(
            recorder.beginRecord(10_000, 60_000, 0, ProgressReason.Periodic),
        )

        recorder.recordFailed(attemptId)

        assertNull(recorder.beginRecord(10_000, 60_000, 14_999, ProgressReason.Periodic))
        assertNotNull(recorder.beginRecord(10_000, 60_000, 15_000, ProgressReason.Periodic))
    }

    @Test
    fun `older failure does not invalidate a newer pending save`() {
        val recorder = PlaybackProgressRecorder()
        val firstAttempt = checkNotNull(
            recorder.beginRecord(10_000, 60_000, 0, ProgressReason.Started),
        )
        val secondAttempt = checkNotNull(
            recorder.beginRecord(20_000, 60_000, 1_000, ProgressReason.Seeked),
        )

        recorder.recordFailed(firstAttempt)

        assertNull(recorder.beginRecord(20_000, 60_000, 2_000, ProgressReason.Exit))

        recorder.recordFailed(secondAttempt)

        assertNotNull(recorder.beginRecord(20_000, 60_000, 3_000, ProgressReason.Exit))
    }
}
