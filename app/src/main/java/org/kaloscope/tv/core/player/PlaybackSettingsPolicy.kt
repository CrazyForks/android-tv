package org.kaloscope.tv.core.player

import androidx.media3.common.Player

object PlaybackSettingsPolicy {
    fun shouldAutoAdvance(
        playbackState: Int,
        playWhenReady: Boolean,
        autoplayNext: Boolean,
        hasNext: Boolean,
        switchingItem: Boolean,
    ): Boolean =
        // Reaching the end must respect pause intent and any pending manual episode selection.
        playbackState == Player.STATE_ENDED &&
            playWhenReady &&
            autoplayNext &&
            hasNext &&
            !switchingItem
}
