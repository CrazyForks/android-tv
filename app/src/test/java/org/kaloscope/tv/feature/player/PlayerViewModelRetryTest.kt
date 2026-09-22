package org.kaloscope.tv.feature.player

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.DanmakuComment
import org.kaloscope.tv.core.model.MediaProbe
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkSearchResult
import org.kaloscope.tv.core.model.ReaderContent
import org.kaloscope.tv.core.model.ReaderImageContent
import org.kaloscope.tv.core.model.ReaderImagePage
import org.kaloscope.tv.core.model.ResolvedNetworkResource
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.model.SubtitleTrack
import org.kaloscope.tv.core.model.WatchHistoryItem
import org.kaloscope.tv.core.player.LocalEpisodeRef
import org.kaloscope.tv.core.player.PlaybackOrigin
import org.kaloscope.tv.core.player.PlaybackRequest
import org.kaloscope.tv.core.player.PlaybackRequestStore
import org.kaloscope.tv.core.player.TranscodeResolution
import org.kaloscope.tv.data.history.HistoryRepository
import org.kaloscope.tv.data.search.NetworkResourceRepository
import org.kaloscope.tv.test.StubMediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelRetryTest {
    // Cancellation completes retries inline, as it can on Main.immediate.
    private val dispatcher = UnconfinedTestDispatcher()
    private val requestStore = PlaybackRequestStore()
    private val repository = PendingExtrasRepository()
    private val session = Session(
        server = SavedServer("server-id", "Test server", "https://server.example"),
        token = "test-token",
        user = SessionUser(1, "tv_user", "user"),
    )
    private val request = PlaybackRequest.LocalMedia(
        requestId = "request-id",
        serverId = session.server.id,
        mediaId = 301,
        path = "/media/episode-1.mkv",
        title = "Episode 1",
        resumePositionSeconds = null,
        origin = PlaybackOrigin.MediaDetail,
        siblings = listOf(
            LocalEpisodeRef(301, "/media/episode-1.mkv", "Episode 1"),
            LocalEpisodeRef(302, "/media/episode-2.mkv", "Episode 2"),
        ),
    )
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PlayerViewModel(
            requestStore = requestStore,
            mediaRepository = repository,
            historyRepository = unusedHistoryRepository(),
            networkResourceRepository = unusedNetworkResourceRepository(),
        )
        requestStore.put(request)
        viewModel.load(session, request.requestId)
        dispatcher.scheduler.runCurrent()
        repository.suspendRetries = true
        viewModel.retryExtra(session, PlayerExtra.Subtitles)
        viewModel.retryExtra(session, PlayerExtra.Danmakus)
        assertEquals(setOf(PlayerExtra.Subtitles, PlayerExtra.Danmakus), repository.startedRetries)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `closing player cancels both retries and releases the active request`() = runTest(dispatcher) {
        viewModel.close(request.requestId)

        assertEquals(repository.startedRetries, repository.cancelledRetries)
        assertNull(requestStore.get(request.requestId))
        viewModel.load(session, request.requestId)
        runCurrent()
        assertEquals(PlayerUiState.MissingRequest, viewModel.uiState.value)
    }

    @Test
    fun `switching episode cancels both retries and loads the selected episode`() = runTest(dispatcher) {
        repository.suspendRetries = false

        viewModel.selectEpisode(session, episodeIndex = 1)
        runCurrent()

        assertEquals(repository.startedRetries, repository.cancelledRetries)
        val content = viewModel.uiState.value as PlayerUiState.Content
        val selected = content.request as PlaybackRequest.LocalMedia
        assertEquals(302L, selected.mediaId)
        assertEquals(selected, requestStore.get(request.requestId))
        assertFalse(content.switchingItem)
    }

    @Test
    fun `clearing server cancels both retries and removes its stored requests`() = runTest(dispatcher) {
        viewModel.clearServer(session.server.id)

        assertEquals(repository.startedRetries, repository.cancelledRetries)
        assertNull(requestStore.get(request.requestId))
        assertEquals(PlayerUiState.Loading(), viewModel.uiState.value)
    }
}

private class PendingExtrasRepository : StubMediaRepository() {
    var suspendRetries = false
    val startedRetries = mutableSetOf<PlayerExtra>()
    val cancelledRetries = mutableSetOf<PlayerExtra>()

    override suspend fun getMediaProbe(session: Session, path: String): AppResult<MediaProbe> =
        AppResult.Success(MediaProbe(durationMillis = 90_000, chapters = emptyList()))

    override suspend fun getSubtitleTracks(
        session: Session,
        path: String,
    ): AppResult<List<SubtitleTrack>> {
        if (suspendRetries) awaitRetryCancellation(PlayerExtra.Subtitles)
        return AppResult.Failure(AppError.Offline)
    }

    override suspend fun getDanmakus(
        session: Session,
        path: String,
    ): AppResult<List<DanmakuComment>> {
        if (suspendRetries) awaitRetryCancellation(PlayerExtra.Danmakus)
        return AppResult.Failure(AppError.Offline)
    }

    private suspend fun awaitRetryCancellation(extra: PlayerExtra): Nothing {
        startedRetries += extra
        try {
            awaitCancellation()
        } finally {
            cancelledRetries += extra
        }
    }
}

private fun unusedHistoryRepository() = object : HistoryRepository {
    override suspend fun getRecentVideos(
        session: Session,
    ): AppResult<List<WatchHistoryItem>> = error("Not used")

    override suspend fun recordVideoProgress(
        session: Session,
        mediaId: Long,
        positionSeconds: Long,
        percentage: Int,
    ): AppResult<Unit> = error("Not used")
}

private fun unusedNetworkResourceRepository() = object : NetworkResourceRepository {
    override suspend fun resolveResource(
        session: Session,
        indexerId: Long,
        result: NetworkSearchResult,
        preferredDefinition: TranscodeResolution,
    ): AppResult<ResolvedNetworkResource> = error("Not used")

    override suspend fun resolveVideoChapter(
        session: Session,
        source: NetworkPlaybackSource,
        chapterIndex: Int,
        preferredDefinition: TranscodeResolution,
    ): AppResult<NetworkPlaybackSource> = error("Not used")

    override suspend fun resolveReaderChapter(
        session: Session,
        content: ReaderContent,
        chapterIndex: Int,
    ): AppResult<ReaderContent> = error("Not used")

    override suspend fun loadImagePage(
        session: Session,
        content: ReaderImageContent,
    ): AppResult<ReaderImagePage> = error("Not used")
}
