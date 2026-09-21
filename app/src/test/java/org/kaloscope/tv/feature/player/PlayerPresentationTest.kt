package org.kaloscope.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkVideoType
import org.kaloscope.tv.core.player.PlaybackOrigin
import org.kaloscope.tv.core.player.PlaybackRequest

class PlayerPresentationTest {
    @Test
    fun `quality changes retain item identity but replace playback source`() {
        val original = networkRequest()
        val updated = original.copy(
            source = original.source.copy(
                url = "https://media.example/video-720.mp4",
                selectedDefinitionIndex = 1,
            ),
            resumePositionMillis = 15_000,
        )

        assertEquals(original.playbackItemIdentity(), updated.playbackItemIdentity())
        assertNotEquals(original.playbackIdentity(), updated.playbackIdentity())
    }

    @Test
    fun `changing network resource resets item identity`() {
        val original = networkRequest()
        val updated = original.copy(source = original.source.copy(resourceId = "next-resource"))

        assertNotEquals(original.playbackItemIdentity(), updated.playbackItemIdentity())
    }

    @Test
    fun `changing chapter within the same resource resets item identity`() {
        val original = networkRequest()
        val updated = original.copy(source = original.source.copy(selectedChapterIndex = 1))

        assertNotEquals(original.playbackItemIdentity(), updated.playbackItemIdentity())
    }

    @Test
    fun `new request for the same network video resets item identity`() {
        val original = networkRequest()
        val updated = original.copy(requestId = "new-request")

        assertNotEquals(original.playbackItemIdentity(), updated.playbackItemIdentity())
    }

    @Test
    fun `changing local episode resets item identity`() {
        val original = localRequest()
        val updated = original.copy(mediaId = 2)

        assertNotEquals(original.playbackItemIdentity(), updated.playbackItemIdentity())
    }

    @Test
    fun `new request for the same local media resets item identity`() {
        val original = localRequest()
        val updated = original.copy(requestId = "new-request")

        assertNotEquals(original.playbackItemIdentity(), updated.playbackItemIdentity())
    }

    @Test
    fun `quality control uses selected definition without playback source`() {
        assertEquals(
            "480P 清晰 HEVC",
            playerQualityControlLabel(
                playbackModeLabel = "网络资源",
                selectedDefinitionLabel = "480P 清晰 HEVC",
            ),
        )
    }

    @Test
    fun `quality control falls back when selected definition is blank`() {
        assertEquals(
            "网络资源",
            playerQualityControlLabel(
                playbackModeLabel = "网络资源",
                selectedDefinitionLabel = "   ",
            ),
        )
    }

    private fun networkRequest() = PlaybackRequest.NetworkVideo(
        requestId = "request",
        serverId = "server",
        title = "Video",
        source = NetworkPlaybackSource(
            indexerId = 1,
            resourceId = "resource",
            title = "Video",
            url = "https://media.example/video-1080.mp4",
            videoType = NetworkVideoType.Mp4,
            danmakus = emptyList(),
            selectedDefinitionIndex = 0,
            selectedChapterIndex = 0,
        ),
    )

    private fun localRequest() = PlaybackRequest.LocalMedia(
        requestId = "request",
        serverId = "server",
        mediaId = 1,
        path = "/video.mp4",
        title = "Video",
        resumePositionSeconds = 0,
        origin = PlaybackOrigin.MediaDetail,
    )
}
