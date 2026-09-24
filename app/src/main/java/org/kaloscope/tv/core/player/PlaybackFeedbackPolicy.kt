package org.kaloscope.tv.core.player

import androidx.media3.common.Player

enum class PlaybackFeedback {
    Preparing,
    Ready,
    Rebuffering,
    FallingBack,
    SwitchingItem,
    Failed,
}

object PlaybackFeedbackPolicy {
    fun resolve(
        playbackState: Int,
        hasBeenReady: Boolean,
        fallbackInProgress: Boolean,
        switchingItem: Boolean,
        failure: PlaybackFailure?,
        playWhenReady: Boolean,
    ): PlaybackFeedback =
        when {
            failure != null -> PlaybackFeedback.Failed
            switchingItem -> PlaybackFeedback.SwitchingItem
            fallbackInProgress -> PlaybackFeedback.FallingBack
            // Resuming at the end can reach STATE_ENDED without ever reaching STATE_READY.
            !hasBeenReady &&
                playbackState != Player.STATE_READY &&
                playbackState != Player.STATE_ENDED ->
                PlaybackFeedback.Preparing

            PlaybackBufferingPolicy.isRebuffering(
                hasBeenReady = hasBeenReady,
                playbackState = playbackState,
                playWhenReady = playWhenReady,
            ) ->
                PlaybackFeedback.Rebuffering

            else -> PlaybackFeedback.Ready
        }
}
