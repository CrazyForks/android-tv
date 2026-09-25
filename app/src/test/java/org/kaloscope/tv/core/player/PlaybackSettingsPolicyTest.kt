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
                playWhenReady = true,
                autoplayNext = true,
                hasNext = true,
                switchingItem = false,
            ),
        )
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_READY,
                playWhenReady = true,
                autoplayNext = true,
                hasNext = true,
                switchingItem = false,
            ),
        )
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                playWhenReady = true,
                autoplayNext = false,
                hasNext = true,
                switchingItem = false,
            ),
        )
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                playWhenReady = true,
                autoplayNext = true,
                hasNext = false,
                switchingItem = false,
            ),
        )
    }

    @Test
    fun `paused playback does not auto advance at the end`() {
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                playWhenReady = false,
                autoplayNext = true,
                hasNext = true,
                switchingItem = false,
            ),
        )
    }

    @Test
    fun `autoplay does not interrupt an item switch`() {
        assertFalse(
            PlaybackSettingsPolicy.shouldAutoAdvance(
                playbackState = Player.STATE_ENDED,
                playWhenReady = true,
                autoplayNext = true,
                hasNext = true,
                switchingItem = true,
            ),
        )
    }
}
