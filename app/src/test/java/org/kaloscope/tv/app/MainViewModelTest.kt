package org.kaloscope.tv.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.model.WatchHistoryItem
import org.kaloscope.tv.data.history.HistoryRepository
import org.kaloscope.tv.feature.home.HomeUiState

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PendingHistoryRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = PendingHistoryRepository()
        viewModel = MainViewModel(repository)
    }

    @After
    fun tearDown() {
        repository.requests.forEach { it.result.cancel() }
        viewModel.reset()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `late load cannot overwrite a newer refresh`() = runTest(dispatcher) {
        viewModel.loadHome(session())
        runCurrent()
        val oldRequest = repository.requests.single()

        viewModel.loadHome(session(), force = true)
        runCurrent()
        val latestItems = listOf(historyItem(501))
        repository.requests.last().result.complete(AppResult.Success(latestItems))
        runCurrent()

        oldRequest.result.complete(AppResult.Success(listOf(historyItem(301))))
        runCurrent()

        assertEquals(HomeUiState.Content(latestItems), viewModel.homeState.value)
        assertEquals(2, repository.requests.size)
    }

    @Test
    fun `late refresh failure cannot restore history after a newer empty result`() = runTest(dispatcher) {
        viewModel.loadHome(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(listOf(historyItem(301))))
        runCurrent()
        viewModel.loadHome(session(), force = true)
        runCurrent()
        val oldRefresh = repository.requests.last()

        viewModel.loadHome(session(), force = true)
        runCurrent()
        repository.requests.last().result.complete(AppResult.Success(emptyList()))
        runCurrent()

        oldRefresh.result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()

        assertEquals(HomeUiState.Empty, viewModel.homeState.value)
        assertEquals(3, repository.requests.size)
    }

    @Test
    fun `late authorization failure cannot invalidate another server's history`() = runTest(dispatcher) {
        viewModel.loadHome(session())
        runCurrent()
        val oldRequest = repository.requests.single()

        viewModel.reset()
        val newSession = session().copy(
            server = SavedServer("other-server", "Other server", "https://other.example"),
        )
        viewModel.loadHome(newSession)
        runCurrent()
        val latestItems = listOf(historyItem(501))
        repository.requests.last().result.complete(AppResult.Success(latestItems))
        runCurrent()

        oldRequest.result.complete(AppResult.Failure(AppError.Unauthorized))
        runCurrent()

        assertFalse(viewModel.homeState.value.hasUnauthorized())
        assertEquals(HomeUiState.Content(latestItems), viewModel.homeState.value)
        assertEquals(listOf("server-id", "other-server"), repository.requests.map { it.serverId })
    }

    @Test
    fun `reset discards late history and allows reloading the same server`() = runTest(dispatcher) {
        viewModel.loadHome(session())
        runCurrent()
        val oldRequest = repository.requests.single()

        viewModel.reset()
        oldRequest.result.complete(AppResult.Success(listOf(historyItem(301))))
        runCurrent()

        assertEquals(HomeUiState.Loading, viewModel.homeState.value)

        viewModel.loadHome(session())
        runCurrent()
        val latestItems = listOf(historyItem(501))
        repository.requests.last().result.complete(AppResult.Success(latestItems))
        runCurrent()

        assertEquals(HomeUiState.Content(latestItems), viewModel.homeState.value)
        assertEquals(2, repository.requests.size)
    }
}

private class PendingHistoryRepository : HistoryRepository {
    val requests = mutableListOf<PendingHistoryRequest>()

    override suspend fun getRecentVideos(session: Session): AppResult<List<WatchHistoryItem>> {
        val request = PendingHistoryRequest(session.server.id)
        requests += request
        // Model a repository result arriving after the caller cancels its request.
        return withContext(NonCancellable) { request.result.await() }
    }

    override suspend fun recordVideoProgress(
        session: Session,
        mediaId: Long,
        positionSeconds: Long,
        percentage: Int,
    ): AppResult<Unit> = error("Not used")
}

private class PendingHistoryRequest(
    val serverId: String,
    val result: CompletableDeferred<AppResult<List<WatchHistoryItem>>> = CompletableDeferred(),
)

private fun historyItem(mediaId: Long) = WatchHistoryItem(
    historyId = mediaId,
    mediaId = mediaId,
    title = "Video $mediaId",
    fileName = "video-$mediaId.mkv",
    path = "/media/video-$mediaId.mkv",
    positionSeconds = 120,
    percentage = 10,
    year = null,
    season = null,
    episode = null,
    posterPath = null,
    backdropPath = null,
    rating = null,
    updatedAt = "2026-09-22T08:00:00Z",
)

private fun session() = Session(
    server = SavedServer("server-id", "Test server", "https://server.example"),
    token = "test-token",
    user = SessionUser(1, "tv_user", "user"),
)
