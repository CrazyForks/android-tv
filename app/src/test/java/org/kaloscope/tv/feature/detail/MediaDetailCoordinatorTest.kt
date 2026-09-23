package org.kaloscope.tv.feature.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kaloscope.tv.app.hasUnauthorized
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.GridViewportSnapshot
import org.kaloscope.tv.core.model.MediaActor
import org.kaloscope.tv.core.model.MediaDetail
import org.kaloscope.tv.core.model.MediaLibrary
import org.kaloscope.tv.core.model.MediaLibraryType
import org.kaloscope.tv.core.model.MediaSummary
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.test.StubMediaRepository

class MediaDetailCoordinatorTest {
    @Test
    fun `loads real media detail`() = runBlocking {
        val detail = detail(201)
        val coordinator = MediaDetailCoordinator(
            DetailFakeRepository(mutableListOf(AppResult.Success(detail))),
        )

        coordinator.load(session(), 201)

        assertEquals(MediaDetailUiState.Content(detail), coordinator.state.value)
    }

    @Test
    fun `detail error remains retryable`() = runBlocking {
        val coordinator = MediaDetailCoordinator(
            DetailFakeRepository(mutableListOf(AppResult.Failure(AppError.Offline))),
        )

        coordinator.load(session(), 201)

        assertEquals(MediaDetailUiState.Error(AppError.Offline), coordinator.state.value)
    }

    @Test
    fun `first content waits for the initial episode detail`() =
        runBlocking {
            val parent = detail(
                201,
                children = listOf(
                    summary(100, season = 0),
                    summary(301, season = 1),
                ),
            )
            val first = detail(100).copy(plot = "Initial episode plot")
            val pendingChild = CompletableDeferred<AppResult<MediaDetail>>()
            val calls = mutableListOf<Long>()
            val repository = object : StubMediaRepository() {
                override suspend fun getMediaDetail(
                    session: Session,
                    mediaId: Long,
                ): AppResult<MediaDetail> {
                    calls += mediaId
                    return if (mediaId == parent.id) AppResult.Success(parent) else pendingChild.await()
                }
            }
            val coordinator = MediaDetailCoordinator(repository)

            val loading = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.load(session(), 201)
            }

            assertEquals(MediaDetailUiState.Loading, coordinator.state.value)
            assertEquals(listOf(201L, 100L), calls)
            pendingChild.complete(AppResult.Success(first))
            loading.await()

            val content = coordinator.state.value as MediaDetailUiState.Content
            assertEquals(100L, content.focusedChildId)
            assertEquals(first, content.focusedChildDetail)
            assertEquals(listOf(201L, 100L), calls)
        }

    @Test
    fun `focus and viewport updates never call repository`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301), summary(302)))
        val repository = DetailFakeRepository(
            mutableListOf(AppResult.Success(parent), AppResult.Success(detail(301))),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)

        coordinator.rememberFocusedChild(302)
        coordinator.rememberChildViewport(GridViewportSnapshot(1, 24))

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(302L, content.focusedChildId)
        assertEquals(GridViewportSnapshot(1, 24), content.childViewport)
        assertEquals(listOf(201L, 301L), repository.detailCalls)
    }

    @Test
    fun `focus moves across season numbers without repository calls`() = runBlocking {
        val parent = detail(
            201,
            children = listOf(
                summary(100, season = 0),
                summary(301, season = 1),
            ),
        )
        val repository = DetailFakeRepository(
            mutableListOf(AppResult.Success(parent), AppResult.Success(detail(100))),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)

        coordinator.rememberFocusedChild(301)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(301L, content.focusedChildId)
        assertEquals(listOf(201L, 100L), repository.detailCalls)
    }

    @Test
    fun `initial child failure retains parent content without automatic retry`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301)))
        val repository = DetailFakeRepository(
            mutableListOf(
                AppResult.Success(parent),
                AppResult.Failure(AppError.Offline),
            ),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)

        coordinator.loadFocusedChildAndNeighbors(session(), 301)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(parent, content.parent)
        assertEquals(301L, content.focusedChildId)
        assertEquals(null, content.focusedChildDetail)
        assertEquals(AppError.Offline, content.childDetailError)
        assertEquals(listOf(201L, 301L), repository.detailCalls)
    }

    @Test
    fun `initial child response must match the selected episode`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301)))
        val coordinator = MediaDetailCoordinator(
            DetailFakeRepository(
                mutableListOf(AppResult.Success(parent), AppResult.Success(detail(999))),
            ),
        )

        coordinator.load(session(), 201)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(parent, content.parent)
        assertEquals(301L, content.focusedChildId)
        assertEquals(null, content.focusedChildDetail)
        assertEquals(AppError.InvalidData("media child detail"), content.childDetailError)
    }

    @Test
    fun `late initial episode cannot replace a newer load of the same series`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301)))
        val pendingChild = CompletableDeferred<AppResult<MediaDetail>>()
        val freshChild = detail(301).copy(plot = "Fresh episode plot")
        var childRequests = 0
        val repository = object : StubMediaRepository() {
            override suspend fun getMediaDetail(session: Session, mediaId: Long): AppResult<MediaDetail> {
                if (mediaId == parent.id) return AppResult.Success(parent)
                childRequests += 1
                return if (childRequests == 1) pendingChild.await() else AppResult.Success(freshChild)
            }
        }
        val coordinator = MediaDetailCoordinator(repository)
        val previousLoad = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(session(), 201)
        }

        coordinator.reset()
        coordinator.load(session(), 201)
        pendingChild.complete(AppResult.Success(detail(301).copy(plot = "Old episode plot")))
        previousLoad.await()
        coordinator.rememberFocusedChild(301)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(freshChild, content.focusedChildDetail)
    }

    @Test
    fun `neighbor prefetch preserves selection and makes its detail immediately available`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301), summary(302), summary(303)))
        val first = detail(301).copy(plot = "First episode plot")
        val second = detail(302).copy(plot = "Second episode plot")
        val repository = DetailFakeRepository(
            mutableListOf(AppResult.Success(parent), AppResult.Success(first), AppResult.Success(second)),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)

        coordinator.loadFocusedChildAndNeighbors(session(), 301)

        val initialContent = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(first, initialContent.focusedChildDetail)
        assertEquals(listOf(201L, 301L, 302L), repository.detailCalls)

        coordinator.rememberFocusedChild(302)

        val selectedContent = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(second, selectedContent.focusedChildDetail)
        assertEquals(listOf(201L, 301L, 302L), repository.detailCalls)
    }

    @Test
    fun `neighbor network failure does not replace the selected episode`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301), summary(302)))
        val first = detail(301)
        val coordinator = MediaDetailCoordinator(
            DetailFakeRepository(
                mutableListOf(
                    AppResult.Success(parent),
                    AppResult.Success(first),
                    AppResult.Failure(AppError.Offline),
                ),
            ),
        )
        coordinator.load(session(), 201)

        coordinator.loadFocusedChildAndNeighbors(session(), 301)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(first, content.focusedChildDetail)
        assertEquals(null, content.childDetailError)
    }

    @Test
    fun `neighbor authentication failure remains visible to root session handling`() = runBlocking {
        val parent = detail(201, children = listOf(summary(300), summary(301), summary(302)))
        val selected = detail(301)
        val repository = DetailFakeRepository(
            mutableListOf(
                AppResult.Success(parent),
                AppResult.Success(detail(300)),
                AppResult.Success(selected),
                AppResult.Failure(AppError.Unauthorized),
            ),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)
        coordinator.rememberFocusedChild(301)

        coordinator.loadFocusedChildAndNeighbors(session(), 301)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(selected, content.focusedChildDetail)
        assertEquals(AppError.Unauthorized, content.childDetailError)
        assertEquals(listOf(201L, 300L, 301L, 302L), repository.detailCalls)
    }

    @Test
    fun `initial episode focus preserves authentication failure until reset`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301)))
        val repository = DetailFakeRepository(
            mutableListOf(
                AppResult.Success(parent),
                AppResult.Failure(AppError.Unauthorized),
            ),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)
        val failed = coordinator.state.value

        coordinator.rememberFocusedChild(301)

        assertEquals(failed, coordinator.state.value)
        assertTrue(coordinator.state.value.hasUnauthorized())
        coordinator.loadFocusedChildAndNeighbors(session(), 301)
        assertEquals(listOf(201L, 301L), repository.detailCalls)

        coordinator.reset()
        assertEquals(MediaDetailUiState.Loading, coordinator.state.value)
    }

    @Test
    fun `moving focus preserves a neighbor authentication failure`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301), summary(302), summary(303)))
        val first = detail(301)
        val second = detail(302)
        val repository = DetailFakeRepository(
            mutableListOf(
                AppResult.Success(parent),
                AppResult.Success(first),
                AppResult.Success(second),
                AppResult.Failure(AppError.Unauthorized),
            ),
        )
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)
        coordinator.rememberFocusedChild(302)
        coordinator.loadFocusedChildAndNeighbors(session(), 302)
        val failed = coordinator.state.value as MediaDetailUiState.Content

        for ((childId, cachedDetail) in listOf(301L to first, 303L to null)) {
            coordinator.rememberFocusedChild(childId)

            assertEquals(
                failed.copy(focusedChildId = childId, focusedChildDetail = cachedDetail),
                coordinator.state.value,
            )
            assertTrue(coordinator.state.value.hasUnauthorized())
            coordinator.loadFocusedChildAndNeighbors(session(), childId)
            assertEquals(listOf(201L, 301L, 302L, 303L), repository.detailCalls)
        }
    }

    @Test
    fun `refocusing an episode clears ordinary errors and allows retry`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301)))
        val child = detail(301)
        for (error in listOf(AppError.Offline, AppError.Forbidden)) {
            val repository = DetailFakeRepository(
                mutableListOf(
                    AppResult.Success(parent),
                    AppResult.Failure(error),
                    AppResult.Success(child),
                ),
            )
            val coordinator = MediaDetailCoordinator(repository)
            coordinator.load(session(), 201)

            coordinator.rememberFocusedChild(301)
            coordinator.loadFocusedChildAndNeighbors(session(), 301)

            assertEquals(
                MediaDetailUiState.Content(
                    parent = parent,
                    focusedChildId = 301,
                    focusedChildDetail = child,
                ),
                coordinator.state.value,
            )
            assertEquals(listOf(201L, 301L, 301L), repository.detailCalls)
        }
    }

    @Test
    fun `late child result cannot refill the cache after reloading the same parent`() = runBlocking {
        val parent = detail(201, children = listOf(summary(301), summary(302)))
        val pendingChild = CompletableDeferred<AppResult<MediaDetail>>()
        val repository = object : StubMediaRepository() {
            override suspend fun getMediaDetail(session: Session, mediaId: Long): AppResult<MediaDetail> =
                when (mediaId) {
                    parent.id -> AppResult.Success(parent)
                    301L -> AppResult.Success(detail(301))
                    else -> pendingChild.await()
                }
        }
        val coordinator = MediaDetailCoordinator(repository)
        coordinator.load(session(), 201)
        val previousRequest = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.loadFocusedChildAndNeighbors(session(), 301)
        }

        coordinator.reset()
        coordinator.load(session(), 201)
        pendingChild.complete(AppResult.Success(detail(302)))
        previousRequest.await()
        coordinator.rememberFocusedChild(302)

        val content = coordinator.state.value as MediaDetailUiState.Content
        assertEquals(null, content.focusedChildDetail)
    }
}

private class DetailFakeRepository(
    private val details: MutableList<AppResult<MediaDetail>>,
) : StubMediaRepository() {
    val detailCalls = mutableListOf<Long>()

    override suspend fun getMediaDetail(
        session: Session,
        mediaId: Long,
    ): AppResult<MediaDetail> {
        detailCalls += mediaId
        return details.removeAt(0)
    }
}

private fun detail(
    id: Long,
    title: String = "群星档案",
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
    season = null,
    episode = null,
    aired = null,
    plot = "简介",
    genres = listOf("科幻"),
    directors = emptyList(),
    writers = emptyList(),
    studios = emptyList(),
    actors = listOf(MediaActor("沈川", "队长", null)),
    children = children,
)

private fun summary(
    id: Long,
    season: Int = 1,
) = MediaSummary(
    id = id,
    title = "启程",
    path = "/media/$id",
    posterPath = null,
    backdropPath = null,
    year = 2026,
    rating = null,
    season = season,
    episode = 1,
)

private fun session() = Session(
    server = SavedServer("server-id", "家庭服务器", "http://127.0.0.1:8000"),
    token = "token",
    user = SessionUser(1, "tv_user", "user"),
)
