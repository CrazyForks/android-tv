package org.kaloscope.tv.feature.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.model.WatchHistoryItem
import org.kaloscope.tv.data.history.HistoryRepository

class HomeCoordinatorTest {
    @Test
    fun `loads real history into content state`() = runBlocking {
        val item = historyItem()
        val coordinator = HomeCoordinator(
            FakeHistoryRepository(AppResult.Success(listOf(item))),
        )

        coordinator.load(session())

        assertEquals(HomeUiState.Content(listOf(item)), coordinator.state.value)
    }

    @Test
    fun `carousel keeps latest record per parent in recent order`() = runBlocking {
        val latestEpisode = historyItem().copy(
            detailMediaId = 201,
            episode = 1,
            updatedAt = "2026-07-23T10:00:00Z",
        )
        val otherParent = historyItem().copy(
            historyId = 402,
            mediaId = 302,
            detailMediaId = 202,
            updatedAt = "2026-07-23T09:00:00Z",
        )
        val olderEpisode = historyItem().copy(
            historyId = 403,
            mediaId = 303,
            detailMediaId = 201,
            episode = 8,
            updatedAt = "2026-07-23T08:00:00Z",
        )
        val items = listOf(latestEpisode, otherParent, olderEpisode)
        val coordinator = HomeCoordinator(
            FakeHistoryRepository(AppResult.Success(items)),
        )

        coordinator.load(session())

        val state = coordinator.state.value as HomeUiState.Content
        assertEquals(listOf(latestEpisode, otherParent), state.carouselItems)
        assertEquals(items, state.items)
    }

    @Test
    fun `carousel keeps independent media and different parents separate`() = runBlocking {
        val movie = historyItem().copy(
            season = null,
            episode = null,
        )
        val anotherMovie = movie.copy(
            historyId = 402,
            mediaId = 302,
            detailMediaId = 302,
        )
        val firstSeason = historyItem().copy(
            historyId = 403,
            mediaId = 303,
            detailMediaId = 201,
            parentTitle = "群星档案",
        )
        val secondSeason = firstSeason.copy(
            historyId = 404,
            mediaId = 304,
            detailMediaId = 202,
            season = 2,
        )
        val items = listOf(movie, anotherMovie, firstSeason, secondSeason)
        val coordinator = HomeCoordinator(
            FakeHistoryRepository(AppResult.Success(items)),
        )

        coordinator.load(session())

        assertEquals(items, (coordinator.state.value as HomeUiState.Content).carouselItems)
    }

    @Test
    fun `refresh replaces parent card with most recently watched episode`() = runBlocking {
        val previous = historyItem().copy(detailMediaId = 201)
        val repository = FakeHistoryRepository(AppResult.Success(listOf(previous)))
        val coordinator = HomeCoordinator(repository)
        coordinator.load(session())

        val latest = previous.copy(
            historyId = 402,
            mediaId = 302,
            episode = 2,
            positionSeconds = 120,
            percentage = 5,
            updatedAt = "2026-07-23T09:00:00Z",
        )
        repository.result = AppResult.Success(listOf(latest, previous))
        coordinator.load(session())

        val state = coordinator.state.value as HomeUiState.Content
        assertEquals(listOf(latest), state.carouselItems)
        assertEquals(listOf(latest, previous), state.items)
    }

    @Test
    fun `restores older episode to latest carousel card for its parent`() {
        val latest = historyItem().copy(detailMediaId = 201)
        val older = latest.copy(
            historyId = 402,
            mediaId = 302,
            updatedAt = "2026-07-23T07:00:00Z",
        )
        val otherParent = historyItem().copy(
            historyId = 403,
            mediaId = 303,
            detailMediaId = 202,
            updatedAt = "2026-07-23T09:00:00Z",
        )
        val state = HomeUiState.Content(listOf(otherParent, latest, older))

        assertEquals(301L, state.carouselMediaIdFor(302L))
        assertEquals(301L, state.carouselMediaIdFor(301L))
        assertNull(state.carouselMediaIdFor(999L))
        assertNull(state.carouselMediaIdFor(null))
    }

    @Test
    fun `uses empty state when server has no history`() = runBlocking {
        val coordinator = HomeCoordinator(
            FakeHistoryRepository(AppResult.Success(emptyList())),
        )

        coordinator.load(session())

        assertEquals(HomeUiState.Empty, coordinator.state.value)
    }

    @Test
    fun `exposes a retryable error when history fails`() = runBlocking {
        val coordinator = HomeCoordinator(
            FakeHistoryRepository(AppResult.Failure(AppError.Offline)),
        )

        coordinator.load(session())

        assertEquals(HomeUiState.Error(AppError.Offline), coordinator.state.value)
    }

    @Test
    fun `refresh failure retains existing history`() = runBlocking {
        val item = historyItem().copy(detailMediaId = 201)
        val older = item.copy(
            historyId = 402,
            mediaId = 302,
            episode = 2,
            updatedAt = "2026-07-23T07:00:00Z",
        )
        val items = listOf(item, older)
        val repository = FakeHistoryRepository(AppResult.Success(items))
        val coordinator = HomeCoordinator(repository)
        coordinator.load(session())

        repository.result = AppResult.Failure(AppError.Offline)
        coordinator.load(session())

        val state = coordinator.state.value as HomeUiState.Content
        assertEquals(listOf(item), state.carouselItems)
        assertEquals(items, state.items)
        assertEquals(AppError.Offline, state.refreshError)
    }

    @Test
    fun `initial authorization failure blocks loading until session reset`() = runBlocking {
        val repository = FakeHistoryRepository(AppResult.Failure(AppError.Unauthorized))
        val coordinator = HomeCoordinator(repository)
        coordinator.load(session())

        val items = listOf(historyItem())
        repository.result = AppResult.Success(items)
        coordinator.load(session())

        assertEquals(HomeUiState.Error(AppError.Unauthorized), coordinator.state.value)
        assertEquals(1, repository.loadCount)

        coordinator.reset()
        coordinator.load(session())

        assertEquals(HomeUiState.Content(items), coordinator.state.value)
        assertEquals(2, repository.loadCount)
    }

    @Test
    fun `refresh authorization failure retains history until session reset`() = runBlocking {
        val items = listOf(historyItem())
        val repository = FakeHistoryRepository(AppResult.Success(items))
        val coordinator = HomeCoordinator(repository)
        coordinator.load(session())
        repository.result = AppResult.Failure(AppError.Unauthorized)
        coordinator.load(session())

        repository.result = AppResult.Success(emptyList())
        coordinator.load(session())

        assertEquals(
            HomeUiState.Content(items, refreshError = AppError.Unauthorized),
            coordinator.state.value,
        )
        assertEquals(2, repository.loadCount)

        coordinator.reset()
        coordinator.load(session())

        assertEquals(HomeUiState.Empty, coordinator.state.value)
        assertEquals(3, repository.loadCount)
    }

    @Test
    fun `ordinary load and refresh failures remain retryable`() = runBlocking {
        for (error in listOf(AppError.Forbidden, AppError.Offline, AppError.Timeout)) {
            for (retainHistory in listOf(false, true)) {
                val items = listOf(historyItem())
                val repository = FakeHistoryRepository(AppResult.Success(items))
                val coordinator = HomeCoordinator(repository)
                if (retainHistory) coordinator.load(session())
                repository.result = AppResult.Failure(error)
                coordinator.load(session())
                assertEquals(
                    if (retainHistory) HomeUiState.Content(items, refreshError = error)
                    else HomeUiState.Error(error),
                    coordinator.state.value,
                )

                val updatedItems = listOf(historyItem().copy(positionSeconds = 1800))
                repository.result = AppResult.Success(updatedItems)
                coordinator.load(session())

                assertEquals(HomeUiState.Content(updatedItems), coordinator.state.value)
                assertEquals(if (retainHistory) 3 else 2, repository.loadCount)
            }
        }
    }
}

private class FakeHistoryRepository(
    var result: AppResult<List<WatchHistoryItem>>,
) : HistoryRepository {
    var loadCount = 0
        private set

    override suspend fun getRecentVideos(
        session: Session,
    ): AppResult<List<WatchHistoryItem>> {
        loadCount += 1
        return result
    }

    override suspend fun recordVideoProgress(
        session: Session,
        mediaId: Long,
        positionSeconds: Long,
        percentage: Int,
    ): AppResult<Unit> = error("Not used")
}

private fun historyItem() = WatchHistoryItem(
    historyId = 401,
    mediaId = 301,
    title = "启程",
    fileName = "S01E01.mkv",
    path = "/media/tv/S01E01.mkv",
    positionSeconds = 1694,
    percentage = 63,
    year = 2026,
    season = 1,
    episode = 1,
    posterPath = null,
    backdropPath = null,
    rating = 8.5,
    updatedAt = "2026-07-23T08:00:00Z",
)

private fun session() = Session(
    server = SavedServer("server-id", "家庭服务器", "http://127.0.0.1:8000"),
    token = "token",
    user = SessionUser(1, "tv_user", "user"),
)
