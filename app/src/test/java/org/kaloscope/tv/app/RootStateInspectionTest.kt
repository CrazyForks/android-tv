package org.kaloscope.tv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.model.MediaDetail
import org.kaloscope.tv.core.model.NetworkChapter
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkVideoType
import org.kaloscope.tv.core.player.PlaybackRequest
import org.kaloscope.tv.feature.detail.MediaDetailUiState
import org.kaloscope.tv.feature.home.HomeUiState
import org.kaloscope.tv.feature.player.PlayerUiState

class RootStateInspectionTest {
    @Test
    fun `child detail authorization failure invalidates the ready session`() {
        val content = MediaDetailUiState.Content(
            parent = detail(),
            childDetailError = AppError.Unauthorized,
        )

        assertTrue(content.hasUnauthorized())
        assertFalse(content.copy(childDetailError = AppError.Offline).hasUnauthorized())
    }

    @Test
    fun `home refresh authorization failure invalidates the ready session`() {
        val content = HomeUiState.Content(
            items = emptyList(),
            refreshError = AppError.Unauthorized,
        )

        assertTrue(content.hasUnauthorized())
        assertFalse(content.copy(refreshError = AppError.Offline).hasUnauthorized())
    }

    @Test
    fun `network episode authorization failure invalidates the ready session`() {
        val content = networkPlayerContent().copy(switchError = AppError.Unauthorized)

        assertTrue(content.hasUnauthorized())
    }

    @Test
    fun `network episode failures unrelated to authentication preserve the ready session`() {
        val content = networkPlayerContent()
        assertFalse(content.hasUnauthorized())

        for (error in listOf(AppError.Forbidden, AppError.Offline, AppError.Timeout)) {
            assertFalse(
                "Episode error $error must not invalidate the session",
                content.copy(switchError = error).hasUnauthorized(),
            )
        }
    }
}

private fun networkPlayerContent() = PlayerUiState.Content(
    request = PlaybackRequest.NetworkVideo(
        requestId = "request-id",
        serverId = "server-id",
        title = "Episode 1",
        source = NetworkPlaybackSource(
            indexerId = 11,
            resourceId = "series-id",
            title = "Episode 1",
            url = "https://media.example/episode-1.m3u8",
            videoType = NetworkVideoType.Hls,
            danmakus = emptyList(),
            chapters = listOf(
                NetworkChapter("ep-1", null, "Episode 1", null),
                NetworkChapter("ep-2", null, "Episode 2", null),
            ),
            selectedChapterIndex = 0,
        ),
    ),
    subtitles = emptyList(),
    danmakus = emptyList(),
    extraFailures = emptyMap(),
)

private fun detail() = MediaDetail(
    id = 201,
    library = null,
    title = "群星档案",
    path = "/media/201",
    posterPath = null,
    backdropPath = null,
    year = 2026,
    rating = null,
    season = null,
    episode = null,
    aired = null,
    plot = "简介",
    genres = emptyList(),
    directors = emptyList(),
    writers = emptyList(),
    studios = emptyList(),
    actors = emptyList(),
    children = emptyList(),
)
