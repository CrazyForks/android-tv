package org.kaloscope.tv.feature.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.IndexerSourceProfile
import org.kaloscope.tv.core.model.NetworkIndexer
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkSearchPage
import org.kaloscope.tv.core.model.NetworkSearchResult
import org.kaloscope.tv.core.model.ReaderContent
import org.kaloscope.tv.core.model.ReaderImageContent
import org.kaloscope.tv.core.model.ReaderImagePage
import org.kaloscope.tv.core.model.ReaderTextContent
import org.kaloscope.tv.core.model.ResolvedNetworkResource
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.SearchFilterValue
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.player.PlaybackRequestStore
import org.kaloscope.tv.core.player.TranscodeResolution
import org.kaloscope.tv.core.reader.ReaderRequestStore
import org.kaloscope.tv.data.search.NetworkResourceRepository
import org.kaloscope.tv.data.search.SearchRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PendingSearchRepository
    private lateinit var resourceRepository: PendingNetworkResourceRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = PendingSearchRepository()
        resourceRepository = PendingNetworkResourceRepository()
        viewModel = SearchViewModel(
            repository = repository,
            requestStore = PlaybackRequestStore(),
            networkResourceRepository = resourceRepository,
            readerRequestStore = ReaderRequestStore(),
        )
    }

    @After
    fun tearDown() {
        viewModel.reset()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `reselecting current indexer preserves pending first page`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()

        viewModel.selectIndexer(session(), 11)
        runCurrent()

        val request = repository.requests.single()
        assertFalse("Selecting the current indexer must keep its request active", request.cancelled)
        request.result.complete(AppResult.Success(page("v1")))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        assertEquals(listOf("v1"), content.results.items.map { it.id })
    }

    @Test
    fun `reselecting current indexer preserves pending next page`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page("v1", hasNext = true)))
        runCurrent()
        viewModel.loadNext(session())
        runCurrent()

        viewModel.selectIndexer(session(), 11)
        runCurrent()

        assertEquals(listOf(1, 2), repository.requests.map { it.pageNumber })
        val request = repository.requests.last()
        assertFalse("Selecting the current indexer must keep pagination active", request.cancelled)
        request.result.complete(AppResult.Success(page("v2", pageNumber = 2)))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        val results = content.results as SearchResultsState.Content
        assertEquals(listOf("v1", "v2"), results.items.map { it.id })
        assertFalse(results.isLoadingMore)
    }

    @Test
    fun `repeated pagination keeps the same request and allows later pages`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page("v1", hasNext = true)))
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
        pendingPage.result.complete(AppResult.Success(page("v2", pageNumber = 2, hasNext = true)))
        runCurrent()
        viewModel.loadNext(session())
        runCurrent()
        assertEquals(listOf(1, 2, 3), repository.requests.map { it.pageNumber })
        repository.requests.last().result.complete(AppResult.Success(page("v3", pageNumber = 3)))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        val results = content.results as SearchResultsState.Content
        assertEquals(listOf("v1", "v2", "v3"), results.items.map { it.id })
        assertFalse(results.isLoadingMore)
    }

    @Test
    fun `pagination does not cancel a pending search`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page("v1", hasNext = true)))
        runCurrent()
        viewModel.updateQuery("new query")
        viewModel.search(session())
        runCurrent()
        val pendingSearch = repository.requests.last()

        viewModel.loadNext(session())
        runCurrent()

        assertFalse("Pagination must not replace a pending search", pendingSearch.cancelled)
        assertEquals(listOf(1, 1), repository.requests.map { it.pageNumber })
        pendingSearch.result.complete(AppResult.Success(page("new")))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        assertEquals("new query", content.submittedKeyword)
        assertEquals(listOf("new"), content.results.items.map { it.id })
    }

    @Test
    fun `repeated retry preserves the in-flight request`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()
        viewModel.retry(session())
        runCurrent()
        val retryRequest = repository.requests.last()

        repeat(2) {
            viewModel.retry(session())
            runCurrent()
        }
        retryRequest.result.complete(AppResult.Success(page("recovered")))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        assertTrue("Repeated retry must finish loading", content.results is SearchResultsState.Content)
        assertEquals(listOf("recovered"), content.results.items.map { it.id })
        assertFalse(retryRequest.cancelled)
        assertEquals(listOf(1, 1), repository.requests.map { it.pageNumber })
    }

    @Test
    fun `late retry does not cancel a new search`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()
        viewModel.updateQuery("new query")
        viewModel.search(session())
        runCurrent()
        val searchRequest = repository.requests.last()

        viewModel.retry(session())
        runCurrent()
        searchRequest.result.complete(AppResult.Success(page("new")))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        assertEquals("new query", content.submittedKeyword)
        assertEquals(listOf("new"), content.results.items.map { it.id })
        assertFalse(searchRequest.cancelled)
        assertEquals(2, repository.requests.size)
    }

    @Test
    fun `failed retry can be retried again`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()
        viewModel.retry(session())
        runCurrent()
        repository.requests.last().result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()

        val failed = viewModel.uiState.value as SearchUiState.Content
        assertEquals(SearchResultsState.Error(AppError.Offline), failed.results)
        viewModel.retry(session())
        runCurrent()
        repository.requests.last().result.complete(AppResult.Success(page("recovered")))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        assertEquals(listOf("recovered"), content.results.items.map { it.id })
        assertEquals(listOf(1, 1, 1), repository.requests.map { it.pageNumber })
    }

    @Test
    fun `selecting another indexer cancels the pending request`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        val previousRequest = repository.requests.single()

        viewModel.selectIndexer(session(), 12)
        runCurrent()

        assertTrue(previousRequest.cancelled)
        assertEquals(listOf(11L, 12L), repository.requests.map { it.indexerId })
        repository.requests.last().result.complete(AppResult.Success(page("other")))
        runCurrent()
        previousRequest.result.complete(AppResult.Success(page("v1")))
        runCurrent()

        val content = viewModel.uiState.value as SearchUiState.Content
        assertEquals(12L, content.selectedIndexerId)
        assertEquals(listOf("other"), content.results.items.map { it.id })
    }

    @Test
    fun `repeated result clicks preserve the pending resolution and destination`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page("v1")))
        runCurrent()
        viewModel.openResult(session(), "v1")
        runCurrent()
        val pending = resourceRepository.requests.single()

        repeat(2) {
            viewModel.openResult(session(), "v1")
            runCurrent()
        }

        assertFalse("Repeated clicks must not cancel resource resolution", pending.cancelled)
        assertEquals(1, resourceRepository.requests.size)
        pending.result.complete(AppResult.Success(textResource()))
        runCurrent()
        val destination = (viewModel.uiState.value as SearchUiState.Content).pendingDestination
        assertTrue(destination is SearchPendingDestination.Reader)

        viewModel.openResult(session(), "v1")
        runCurrent()

        assertEquals(1, resourceRepository.requests.size)
        assertEquals(
            destination,
            (viewModel.uiState.value as SearchUiState.Content).pendingDestination,
        )
    }

    @Test
    fun `failed resource resolution can be retried`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page("v1")))
        runCurrent()
        viewModel.openResult(session(), "v1")
        runCurrent()
        resourceRepository.requests.single().result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()

        val failed = viewModel.uiState.value as SearchUiState.Content
        assertNull(failed.resolvingResultId)
        assertEquals(AppError.Offline, failed.resolutionError)
        viewModel.openResult(session(), "v1")
        runCurrent()

        assertEquals(2, resourceRepository.requests.size)
        resourceRepository.requests.last().result.complete(AppResult.Success(textResource()))
        runCurrent()
        val retried = viewModel.uiState.value as SearchUiState.Content
        assertNull(retried.resolutionError)
        assertTrue(retried.pendingDestination is SearchPendingDestination.Reader)
    }

    @Test
    fun `cancelled resource resolution can be reopened`() = runTest(dispatcher) {
        viewModel.load(session())
        runCurrent()
        repository.requests.single().result.complete(AppResult.Success(page("v1")))
        runCurrent()
        viewModel.openResult(session(), "v1")
        runCurrent()
        val cancelled = resourceRepository.requests.single()

        assertTrue(viewModel.cancelResolution())
        runCurrent()
        assertTrue(cancelled.cancelled)
        assertNull((viewModel.uiState.value as SearchUiState.Content).resolvingResultId)
        viewModel.openResult(session(), "v1")
        runCurrent()

        assertEquals(2, resourceRepository.requests.size)
        resourceRepository.requests.last().result.complete(AppResult.Success(textResource()))
        runCurrent()
        assertTrue(
            (viewModel.uiState.value as SearchUiState.Content).pendingDestination is
                SearchPendingDestination.Reader,
        )
    }
}

private class PendingSearchRepository : SearchRepository {
    val requests = mutableListOf<PendingSearchPage>()

    override suspend fun getAvailableProfiles(
        session: Session,
    ): AppResult<List<IndexerSourceProfile>> = AppResult.Success(
        listOf(11L, 12L).map { id ->
            IndexerSourceProfile(
                indexer = NetworkIndexer(id, "站点 $id", null),
                pageSize = 20,
                keywordRequired = false,
            )
        },
    )

    override suspend fun search(
        session: Session,
        profile: IndexerSourceProfile,
        keyword: String,
        filters: Map<String, SearchFilterValue>,
        pageNumber: Int,
    ): AppResult<NetworkSearchPage> {
        val request = PendingSearchPage(profile.indexer.id, pageNumber)
        requests += request
        return try {
            request.result.await()
        } catch (error: CancellationException) {
            request.cancelled = true
            throw error
        }
    }
}

private class PendingSearchPage(val indexerId: Long, val pageNumber: Int) {
    val result = CompletableDeferred<AppResult<NetworkSearchPage>>()
    var cancelled = false
}

private class PendingNetworkResourceRepository :
    NetworkResourceRepository by UnusedNetworkResourceRepository {
    val requests = mutableListOf<PendingResourceResolution>()

    override suspend fun resolveResource(
        session: Session,
        indexerId: Long,
        result: NetworkSearchResult,
        preferredDefinition: TranscodeResolution,
    ): AppResult<ResolvedNetworkResource> {
        val request = PendingResourceResolution()
        requests += request
        return try {
            request.result.await()
        } catch (error: CancellationException) {
            request.cancelled = true
            throw error
        }
    }
}

private class PendingResourceResolution {
    val result = CompletableDeferred<AppResult<ResolvedNetworkResource>>()
    var cancelled = false
}

private object UnusedNetworkResourceRepository : NetworkResourceRepository {
    override suspend fun resolveResource(
        session: Session,
        indexerId: Long,
        result: NetworkSearchResult,
        preferredDefinition: TranscodeResolution,
    ): AppResult<ResolvedNetworkResource> = error("Unexpected resource resolution")

    override suspend fun resolveVideoChapter(
        session: Session,
        source: NetworkPlaybackSource,
        chapterIndex: Int,
        preferredDefinition: TranscodeResolution,
    ): AppResult<NetworkPlaybackSource> = error("Unexpected video chapter resolution")

    override suspend fun resolveReaderChapter(
        session: Session,
        content: ReaderContent,
        chapterIndex: Int,
    ): AppResult<ReaderContent> = error("Unexpected reader chapter resolution")

    override suspend fun loadImagePage(
        session: Session,
        content: ReaderImageContent,
    ): AppResult<ReaderImagePage> = error("Unexpected image page request")
}

private fun textResource() = ResolvedNetworkResource.Text(
    ReaderTextContent.network(
        indexerId = 11,
        resourceId = "v1",
        title = "测试文本",
        text = "正文",
    ),
)

private fun page(
    resultId: String,
    pageNumber: Int = 1,
    hasNext: Boolean = false,
) = NetworkSearchPage(
    items = listOf(
        NetworkSearchResult(
            id = resultId,
            title = "视频 $resultId",
            coverPath = null,
            rating = null,
            category = null,
            uploader = null,
            uploadedAt = null,
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
