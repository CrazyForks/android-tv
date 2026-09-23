package org.kaloscope.tv.core.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsPolicyTest {
    @Test
    fun `autoplay advances only after playback ends with a next item`() {
        assertTrue(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                autoplayNext = true,
                hasNext = true,
                switchingItem = false,
            ),
        )
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_READY,
                autoplayNext = true,
                hasNext = true,
                switchingItem = false,
            ),
        )
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                autoplayNext = false,
                hasNext = true,
                switchingItem = false,
            ),
        )
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                autoplayNext = true,
                hasNext = false,
                switchingItem = false,
            ),
        )
    }

    @Test
    fun `autoplay does not interrupt an item switch`() {
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                autoplayNext = true,
                hasNext = true,
                switchingItem = true,
            ),
        )
    }
}
