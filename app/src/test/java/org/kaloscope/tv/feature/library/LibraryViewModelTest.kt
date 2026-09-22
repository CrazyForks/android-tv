package org.kaloscope.tv.feature.library

import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.GridViewportSnapshot
import org.kaloscope.tv.core.model.MediaLibrary
import org.kaloscope.tv.core.model.MediaLibraryType
import org.kaloscope.tv.core.model.MediaPage
import org.kaloscope.tv.core.model.MediaSummary
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.test.StubMediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PendingLibraryRepository
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = PendingLibraryRepository()
        viewModel = LibraryViewModel(repository)
    }

    @After
    fun tearDown() {
        repository.pendingLibraries?.cancel()
        repository.requests.forEach { it.result.cancel() }
        viewModel.reset()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `reselecting current library preserves pending first page`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()

        viewModel.selectLibrary(session(), 21)
        runCurrent()

        val request = repository.requests.single()
        assertFalse("Selecting the current library must keep its request active", request.cancelled)
        request.result.complete(AppResult.Success(page(201)))
        runCurrent()

        val content = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(listOf(201L), content.items.items.map { it.id })
    }

    @Test
    fun `reselecting current library preserves pending next page`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page(201, hasNext = true)))
        runCurrent()
        viewModel.loadNext(session())
        runCurrent()

        viewModel.selectLibrary(session(), 21)
        runCurrent()

        assertEquals(listOf(1, 2), repository.requests.map { it.pageNumber })
        val request = repository.requests.last()
        assertFalse("Selecting the current library must keep pagination active", request.cancelled)
        request.result.complete(AppResult.Success(page(202, pageNumber = 2)))
        runCurrent()

        val content = viewModel.uiState.value as LibraryUiState.Content
        val items = content.items as LibraryItemsState.Content
        assertEquals(listOf(201L, 202L), items.items.map { it.id })
        assertFalse(items.isLoadingMore)
    }

    @Test
    fun `repeated pagination keeps the same request and allows later pages`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page(201, hasNext = true)))
        runCurrent()
        viewModel.loadNext(session())
        runCurrent()
        val pendingPage = repository.requests.last()

        repeat(2) {
            viewModel.loadNext(session())
            runCurrent()
        }

        assertFalse("Duplicate pagination must not cancel the pending page", pendingPage.cancelled)
        assertEquals(listOf(1, 2), repository.requests.map { it.pageNumber })
        pendingPage.result.complete(AppResult.Success(page(202, pageNumber = 2, hasNext = true)))
        runCurrent()
        viewModel.loadNext(session())
        runCurrent()
        assertEquals(listOf(1, 2, 3), repository.requests.map { it.pageNumber })
        repository.requests.last().result.complete(AppResult.Success(page(203, pageNumber = 3)))
        runCurrent()

        val content = viewModel.uiState.value as LibraryUiState.Content
        val items = content.items as LibraryItemsState.Content
        assertEquals(listOf(201L, 202L, 203L), items.items.map { it.id })
        assertFalse(items.isLoadingMore)
    }

    @Test
    fun `pagination does not cancel a pending library search`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page(201, hasNext = true)))
        runCurrent()
        viewModel.updateQuery("new query")
        viewModel.search(session())
        runCurrent()
        val pendingSearch = repository.requests.last()

        viewModel.loadNext(session())
        runCurrent()

        assertFalse("Pagination must not replace a pending search", pendingSearch.cancelled)
        assertEquals(listOf(1, 1), repository.requests.map { it.pageNumber })
        pendingSearch.result.complete(AppResult.Success(page(501)))
        runCurrent()

        val content = viewModel.uiState.value as LibraryUiState.Content
        assertEquals("new query", content.submittedKeyword)
        assertEquals(listOf(501L), content.items.items.map { it.id })
    }

    @Test
    fun `late first page cannot replace the newly selected library`() = runTest(dispatcher) {
        repository.completePageAfterCancellation = true
        viewModel.load(session())
        runCurrent()
        val oldPage = repository.requests.single()

        repository.completePageAfterCancellation = false
        viewModel.selectLibrary(session(), 22)
        runCurrent()
        repository.requests.last().result.complete(AppResult.Success(page(501)))
        runCurrent()
        viewModel.rememberFocusedMedia(501)
        viewModel.rememberGridViewport(GridViewportSnapshot(0, 24))
        val selectedState = viewModel.uiState.value

        oldPage.result.complete(AppResult.Success(page(201)))
        runCurrent()

        assertEquals(selectedState, viewModel.uiState.value)
        assertEquals(listOf(21L, 22L), repository.requests.map { it.libraryId })
    }

    @Test
    fun `late pagination failure cannot replace the new search results`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page(201, hasNext = true)))
        runCurrent()
        repository.completePageAfterCancellation = true
        viewModel.loadNext(session())
        runCurrent()
        val oldPage = repository.requests.last()

        repository.completePageAfterCancellation = false
        viewModel.updateQuery("new query")
        viewModel.search(session())
        runCurrent()
        repository.requests.last().result.complete(AppResult.Success(page(501)))
        runCurrent()
        viewModel.rememberFocusedMedia(501)
        viewModel.rememberGridViewport(GridViewportSnapshot(0, 24))
        val searchState = viewModel.uiState.value

        oldPage.result.complete(AppResult.Failure(AppError.Unauthorized))
        runCurrent()

        assertEquals(searchState, viewModel.uiState.value)
        assertEquals(listOf(1, 2, 1), repository.requests.map { it.pageNumber })
    }

    @Test
    fun `late library list failure cannot replace another server's content`() = runTest(dispatcher) {
        val oldLibraries = CompletableDeferred<AppResult<List<MediaLibrary>>>()
        repository.pendingLibraries = oldLibraries
        viewModel.load(session())
        runCurrent()

        repository.pendingLibraries = null
        val newSession = session().copy(
            server = SavedServer("other-server", "Other server", "https://other.example"),
        )
        viewModel.load(newSession)
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page(501)))
        runCurrent()
        val currentState = viewModel.uiState.value

        oldLibraries.complete(AppResult.Failure(AppError.Unauthorized))
        runCurrent()

        assertEquals(currentState, viewModel.uiState.value)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun `reset discards a late library list without requesting its first page`() = runTest(dispatcher) {
        val oldLibraries = CompletableDeferred<AppResult<List<MediaLibrary>>>()
        repository.pendingLibraries = oldLibraries
        viewModel.load(session())
        runCurrent()

        viewModel.reset()
        oldLibraries.complete(
            AppResult.Success(listOf(MediaLibrary(21, "Old library", MediaLibraryType.Movie))),
        )
        runCurrent()

        assertEquals(LibraryUiState.Loading, viewModel.uiState.value)
        assertTrue(repository.requests.isEmpty())
    }

    @Test
    fun `selecting another library cancels the pending request`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        val previousRequest = repository.requests.single()

        viewModel.selectLibrary(session(), 22)
        runCurrent()

        assertTrue(previousRequest.cancelled)
        assertEquals(listOf(21L, 22L), repository.requests.map { it.libraryId })
        repository.requests.last().result.complete(AppResult.Success(page(501)))
        runCurrent()
        previousRequest.result.complete(AppResult.Success(page(201)))
        runCurrent()

        val content = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(22L, content.selectedLibraryId)
        assertEquals(listOf(501L), content.items.items.map { it.id })
    }
}

private class PendingLibraryRepository : StubMediaRepository() {
    val requests = mutableListOf<PendingMediaPage>()
    var completePageAfterCancellation = false
    var pendingLibraries: CompletableDeferred<AppResult<List<MediaLibrary>>>? = null

    override suspend fun getLibraries(session: Session): AppResult<List<MediaLibrary>> {
        pendingLibraries?.let { return withContext(NonCancellable) { it.await() } }
        return AppResult.Success(
            listOf(
                MediaLibrary(21, "剧集库", MediaLibraryType.TvShow),
                MediaLibrary(22, "电影库", MediaLibraryType.Movie),
            ),
        )
    }

    override suspend fun getMediaPage(
        session: Session,
        libraryId: Long,
        pageNumber: Int,
        pageSize: Int,
        keyword: String?,
    ): AppResult<MediaPage> {
        val request = PendingMediaPage(libraryId, pageNumber)
        requests += request
        return try {
            if (completePageAfterCancellation) {
                withContext(NonCancellable) { request.result.await() }
            } else {
                request.result.await()
            }
        } catch (error: CancellationException) {
            request.cancelled = true
            throw error
        }
    }
}

private class PendingMediaPage(val libraryId: Long, val pageNumber: Int) {
    val result = CompletableDeferred<AppResult<MediaPage>>()
    var cancelled = false
}

private fun page(
    mediaId: Long,
    pageNumber: Int = 1,
    hasNext: Boolean = false,
) = MediaPage(
    items = listOf(
        MediaSummary(
            id = mediaId,
            title = "媒体 $mediaId",
            path = "/media/$mediaId",
            posterPath = null,
            backdropPath = null,
            year = null,
            rating = null,
            season = null,
            episode = null,
        ),
    ),
    total = if (hasNext) pageNumber * 20 + 1 else (pageNumber - 1) * 20 + 1,
    pageNumber = pageNumber,
    pageSize = 20,
    hasNext = hasNext,
)

private fun session() = Session(
    server = SavedServer("server-id", "Test server", "https://server.example"),
    token = "fixture-token",
    user = SessionUser(1, "tv", "user"),
)
