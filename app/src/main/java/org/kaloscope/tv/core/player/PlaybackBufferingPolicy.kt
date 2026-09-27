package org.kaloscope.tv.core.player

import androidx.media3.common.Player

internal object PlaybackBufferingPolicy {
    fun hasBeenReady(
        previouslyReady: Boolean,
        playbackState: Int,
    ): Boolean = previouslyReady || playbackState == Player.STATE_READY

    fun fallbackInProgress(
        previouslyInProgress: Boolean,
        playbackState: Int,
    ): Boolean =
        // Resuming at the end can finish a fallback without ever becoming ready.
        previouslyInProgress &&
            playbackState != Player.STATE_READY &&
            playbackState != Player.STATE_ENDED

    fun isRebuffering(
        hasBeenReady: Boolean,
        playbackState: Int,
        playWhenReady: Boolean,
    ): Boolean =
        hasBeenReady &&
            playbackState == Player.STATE_BUFFERING &&
            playWhenReady
}
