package org.kaloscope.tv.core.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFeedbackPolicyTest {
    @Test
    fun `feedback priority is failure switch fallback prepare rebuffer ready`() {
        assertEquals(
            PlaybackFeedback.Failed,
            feedback(
                failure = PlaybackFailure.Source,
                switchingItem = true,
                fallbackInProgress = true,
            ),
        )
        assertEquals(
            PlaybackFeedback.SwitchingItem,
            feedback(switchingItem = true, fallbackInProgress = true),
        )
        assertEquals(
            PlaybackFeedback.FallingBack,
            feedback(fallbackInProgress = true),
        )
        assertEquals(
            PlaybackFeedback.Preparing,
            feedback(hasBeenReady = false),
        )
        assertEquals(
            PlaybackFeedback.Rebuffering,
            feedback(hasBeenReady = true),
        )
        assertEquals(
            PlaybackFeedback.Ready,
            feedback(
                hasBeenReady = true,
                playWhenReady = false,
            ),
        )
        assertEquals(
            PlaybackFeedback.Ready,
            feedback(
                playbackState = Player.STATE_READY,
                hasBeenReady = true,
            ),
        )
    }

    @Test
    fun `direct fatal error is failed while auto transition is fallback`() {
        assertEquals(
            PlaybackFeedback.Failed,
            feedback(
                playbackState = Player.STATE_IDLE,
                failure = PlaybackFailure.Decoder,
            ),
        )
        assertEquals(
            PlaybackFeedback.FallingBack,
            feedback(
                playbackState = Player.STATE_BUFFERING,
                fallbackInProgress = true,
            ),
        )
    }

    @Test
    fun `ended playback exposes replay controls even without an earlier ready state`() {
        for (hasBeenReady in listOf(false, true)) {
            for (playWhenReady in listOf(false, true)) {
                assertEquals(
                    PlaybackFeedback.Ready,
                    feedback(
                        playbackState = Player.STATE_ENDED,
                        hasBeenReady = hasBeenReady,
                        playWhenReady = playWhenReady,
                    ),
                )
            }
        }
    }

    @Test
    fun `ended playback preserves error and operation feedback priority`() {
        assertEquals(
            PlaybackFeedback.Failed,
            feedback(
                playbackState = Player.STATE_ENDED,
                failure = PlaybackFailure.Source,
                switchingItem = true,
                fallbackInProgress = true,
            ),
        )
        assertEquals(
            PlaybackFeedback.SwitchingItem,
            feedback(
                playbackState = Player.STATE_ENDED,
                switchingItem = true,
                fallbackInProgress = true,
            ),
        )
        assertEquals(
            PlaybackFeedback.FallingBack,
            feedback(playbackState = Player.STATE_ENDED, fallbackInProgress = true),
        )
    }

    @Test
    fun `idle and buffering before first ready remain in preparation`() {
        for (playbackState in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING)) {
            for (playWhenReady in listOf(false, true)) {
                assertEquals(
                    PlaybackFeedback.Preparing,
                    feedback(playbackState = playbackState, playWhenReady = playWhenReady),
                )
            }
        }
    }

    private fun feedback(
        playbackState: Int = Player.STATE_BUFFERING,
        hasBeenReady: Boolean = false,
        fallbackInProgress: Boolean = false,
        switchingItem: Boolean = false,
        failure: PlaybackFailure? = null,
        playWhenReady: Boolean = true,
    ) = PlaybackFeedbackPolicy.resolve(
        playbackState = playbackState,
        hasBeenReady = hasBeenReady,
        fallbackInProgress = fallbackInProgress,
        switchingItem = switchingItem,
        failure = failure,
        playWhenReady = playWhenReady,
    )
}
