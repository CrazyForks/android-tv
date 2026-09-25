package org.kaloscope.tv.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.kaloscope.tv.core.model.ImageReaderSettings
import org.kaloscope.tv.core.model.ReaderChapterOrder
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.TextReaderSettings
import org.kaloscope.tv.core.reader.ReaderRequestStore
import org.kaloscope.tv.data.reader.ReaderContentLoader

@HiltViewModel
class ReaderViewModel @Inject constructor(
    requestStore: ReaderRequestStore,
    contentLoader: ReaderContentLoader,
) : ViewModel() {
    private val coordinator = ReaderCoordinator(requestStore, contentLoader)
    private var loadedRequestId: String? = null
    private var chapterJob: Job? = null
    private var pageJob: Job? = null

    val uiState: StateFlow<ReaderUiState> = coordinator.state

    fun load(
        requestId: String,
        session: Session,
    ) {
        if (loadedRequestId == requestId) return
        cancelContentJobs()
        loadedRequestId = requestId
        coordinator.load(requestId, session)
    }

    fun selectChapter(
        session: Session,
        chapterIndex: Int,
    ) {
        val content = (uiState.value as? ReaderUiState.Active)?.content ?: return
        // No-op selections must not cancel the current page or chapter request.
        if (
            chapterIndex !in content.chapters.indices ||
            content.selectedChapterIndex == chapterIndex
        ) {
            return
        }
        chapterJob?.cancel()
        pageJob?.cancel()
        chapterJob = viewModelScope.launch {
            coordinator.selectChapter(session, chapterIndex)
        }
    }

    fun loadMoreImages(session: Session) {
        if (pageJob?.isActive == true) return
        pageJob = viewModelScope.launch { coordinator.loadMoreImages(session) }
    }

    fun updateImageSettings(settings: ImageReaderSettings) =
        coordinator.updateImageSettings(settings)

    fun updateTextSettings(settings: TextReaderSettings) =
        coordinator.updateTextSettings(settings)

    fun updateChapterOrder(order: ReaderChapterOrder) =
        coordinator.updateChapterOrder(order)

    fun dismissChapterError() = coordinator.dismissChapterError()

    fun dismissPageError() = coordinator.dismissPageError()

    fun close(requestId: String) {
        if (loadedRequestId == requestId) {
            cancelContentJobs()
            loadedRequestId = null
        }
        coordinator.close(requestId)
    }

    fun clearServer(serverId: String) {
        cancelContentJobs()
        loadedRequestId = null
        coordinator.clearServer(serverId)
    }

    private fun cancelContentJobs() {
        chapterJob?.cancel()
        chapterJob = null
        pageJob?.cancel()
        pageJob = null
    }
}
