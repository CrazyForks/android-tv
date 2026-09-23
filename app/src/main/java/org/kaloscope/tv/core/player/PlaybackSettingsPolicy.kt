package org.kaloscope.tv.core.player

import androidx.media3.common.Player

object PlaybackSettingsPolicy {
    fun shouldAutoAdvance(
        playbackState: Int,
        autoplayNext: Boolean,
        hasNext: Boolean,
        switchingItem: Boolean,
    ): Boolean =
        // The old item may end while an explicit episode selection is still loading.
        playbackState == Player.STATE_ENDED && autoplayNext && hasNext && !switchingItem
}
