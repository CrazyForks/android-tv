package org.kaloscope.tv.feature.detail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.MediaDetail
import org.kaloscope.tv.core.model.MediaLibrary
import org.kaloscope.tv.core.model.MediaLibraryType
import org.kaloscope.tv.core.model.MediaSummary
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.test.StubMediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDetailViewModelTest {
    @Test
    fun `selected episode detail loads without a debounce delay`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = DetailViewModelFakeRepository(detailFixtures())
        val viewModel = MediaDetailViewModel(repository)
        try {
            viewModel.load(session(), 201)
            runCurrent()

            val initialContent = viewModel.uiState.value as MediaDetailUiState.Content
            assertEquals("第一集简介", initialContent.focusedChildDetail?.plot)
            assertEquals(listOf(201L, 301L, 302L), repository.detailCalls)

            viewModel.rememberFocusedChild(302)

            val selectedContent = viewModel.uiState.value as MediaDetailUiState.Content
            assertEquals("第二集简介", selectedContent.focusedChildDetail?.plot)
            runCurrent()

            assertEquals(0L, testScheduler.currentTime)
            assertEquals(listOf(201L, 301L, 302L, 303L), repository.detailCalls)
        } finally {
            viewModel.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `returning to a loaded child reuses its cached detail`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = DetailViewModelFakeRepository(detailFixtures())
        val viewModel = MediaDetailViewModel(repository)
        try {
            viewModel.load(session(), 201)
            advanceUntilIdle()

            val firstContent = viewModel.uiState.value as MediaDetailUiState.Content
            assertEquals("第一集简介", firstContent.focusedChildDetail?.plot)

            viewModel.rememberFocusedChild(302)
            advanceUntilIdle()
            viewModel.rememberFocusedChild(301)
            advanceUntilIdle()

            assertEquals(listOf(201L, 301L, 302L, 303L), repository.detailCalls)
        } finally {
            viewModel.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `child detail failure preserves parent content for fallback`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val parent = detailFixtures().getValue(201L)
        val repository = DetailViewModelFakeRepository(mapOf(201L to parent))
        val viewModel = MediaDetailViewModel(repository)
        try {
            viewModel.load(session(), 201)
            advanceUntilIdle()

            val content = viewModel.uiState.value as MediaDetailUiState.Content
            assertEquals(parent, content.parent)
            assertEquals(301L, content.focusedChildId)
            assertEquals(null, content.focusedChildDetail)
            assertEquals(AppError.NotFound, content.childDetailError)
        } finally {
            viewModel.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `leaving during initial episode loading cancels the request without showing content`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val parent = detailFixtures().getValue(201L)
        val calls = mutableListOf<Long>()
        var childCancelled = false
        val repository = object : StubMediaRepository() {
            override suspend fun getMediaDetail(session: Session, mediaId: Long): AppResult<MediaDetail> {
                calls += mediaId
                if (mediaId == parent.id) return AppResult.Success(parent)
                try {
                    awaitCancellation()
                } finally {
                    childCancelled = true
                }
            }
        }
        val viewModel = MediaDetailViewModel(repository)
        try {
            viewModel.load(session(), 201)
            runCurrent()

            assertEquals(MediaDetailUiState.Loading, viewModel.uiState.value)
            assertEquals(listOf(201L, 301L), calls)
            viewModel.reset()
            runCurrent()

            assertEquals(true, childCancelled)
            assertEquals(MediaDetailUiState.Loading, viewModel.uiState.value)
            assertEquals(listOf(201L, 301L), calls)
        } finally {
            viewModel.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `new selection cancels a slow neighbor request and takes priority`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val details = detailFixtures()
        val calls = mutableListOf<Long>()
        var cancelledNeighbors = 0
        val repository = object : StubMediaRepository() {
            override suspend fun getMediaDetail(session: Session, mediaId: Long): AppResult<MediaDetail> {
                calls += mediaId
                if (mediaId == 302L) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledNeighbors += 1
                    }
                }
                return AppResult.Success(details.getValue(mediaId))
            }
        }
        val viewModel = MediaDetailViewModel(repository)
        try {
            viewModel.load(session(), 201)
            runCurrent()
            viewModel.rememberFocusedChild(301)
            runCurrent()

            assertEquals(listOf(201L, 301L, 302L), calls)
            assertEquals(0, cancelledNeighbors)

            viewModel.rememberFocusedChild(303)
            runCurrent()

            val content = viewModel.uiState.value as MediaDetailUiState.Content
            assertEquals("第三集简介", content.focusedChildDetail?.plot)
            assertEquals(listOf(201L, 301L, 302L, 303L, 302L), calls)
            assertEquals(1, cancelledNeighbors)

            viewModel.reset()
            runCurrent()
            assertEquals(2, cancelledNeighbors)
            assertEquals(MediaDetailUiState.Loading, viewModel.uiState.value)
        } finally {
            viewModel.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `changing servers reloads matching media ids instead of reusing cached episodes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = DetailViewModelFakeRepository(detailFixtures())
        val viewModel = MediaDetailViewModel(repository)
        try {
            val firstSession = session()
            viewModel.load(firstSession, 201)
            runCurrent()

            viewModel.load(
                firstSession.copy(
                    server = SavedServer("second-server", "Second server", "http://127.0.0.1:8001"),
                ),
                201,
            )
            runCurrent()

            assertEquals(listOf(201L, 301L, 302L, 201L, 301L, 302L), repository.detailCalls)
        } finally {
            viewModel.reset()
            Dispatchers.resetMain()
        }
    }
}

private class DetailViewModelFakeRepository(
    private val details: Map<Long, MediaDetail>,
) : StubMediaRepository() {
    val detailCalls = mutableListOf<Long>()

    override suspend fun getMediaDetail(
        session: Session,
        mediaId: Long,
    ): AppResult<MediaDetail> {
        detailCalls += mediaId
        return details[mediaId]
            ?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.NotFound)
    }
}

private fun detailFixtures(): Map<Long, MediaDetail> {
    val first = childSummary(301, "启程")
    val second = childSummary(302, "返程")
    val third = childSummary(303, "回声")
    return mapOf(
        201L to detail(201, "群星档案", "整部剧简介", children = listOf(first, second, third)),
        301L to detail(301, "启程", "第一集简介"),
        302L to detail(302, "返程", "第二集简介"),
        303L to detail(303, "回声", "第三集简介"),
    )
}

private fun detail(
    id: Long,
    title: String,
    plot: String,
    children: List<MediaSummary> = emptyList(),
) = MediaDetail(
    id = id,
    library = MediaLibrary(21, "剧集库", MediaLibraryType.TvShow),
    title = title,
    path = "/media/$id",
    posterPath = null,
    backdropPath = null,
    year = 2026,
    rating = 8.8,
    season = if (id == 201L) null else 1,
    episode = if (id == 201L) null else (id - 300).toInt(),
    aired = null,
    plot = plot,
    genres = listOf("科幻"),
    directors = emptyList(),
    writers = emptyList(),
    studios = emptyList(),
    actors = emptyList(),
    children = children,
)

private fun childSummary(id: Long, title: String) = MediaSummary(
    id = id,
    title = title,
    path = "/media/$id",
    posterPath = null,
    backdropPath = null,
    year = 2026,
    rating = null,
    season = 1,
    episode = (id - 300).toInt(),
)

private fun session() = Session(
    server = SavedServer("server-id", "家庭服务器", "http://127.0.0.1:8000"),
    token = "fixture-token",
    user = SessionUser(1, "tv_user", "user"),
)
