package org.kaloscope.tv.feature.detail

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.GridViewportSnapshot
import org.kaloscope.tv.core.model.MediaDetail
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.data.media.MediaRepository

sealed interface MediaDetailUiState {
    data object Loading : MediaDetailUiState

    data class Content(
        val parent: MediaDetail,
        val focusedChildId: Long? = null,
        val focusedChildDetail: MediaDetail? = null,
        val childDetailError: AppError? = null,
        val childViewport: GridViewportSnapshot = GridViewportSnapshot.Top,
    ) : MediaDetailUiState

    data class Error(
        val error: AppError,
    ) : MediaDetailUiState
}

class MediaDetailCoordinator(
    private val repository: MediaRepository,
) {
    private val childDetailCache = mutableMapOf<Long, MediaDetail>()
    private var generation = 0L
    private val mutableState =
        MutableStateFlow<MediaDetailUiState>(MediaDetailUiState.Loading)

    val state: StateFlow<MediaDetailUiState> = mutableState.asStateFlow()

    fun reset() {
        generation += 1
        childDetailCache.clear()
        mutableState.value = MediaDetailUiState.Loading
    }

    suspend fun load(
        session: Session,
        mediaId: Long,
    ) {
        generation += 1
        val requestGeneration = generation
        childDetailCache.clear()
        mutableState.value = MediaDetailUiState.Loading
        val result = repository.getMediaDetail(session, mediaId)
        currentCoroutineContext().ensureActive()
        if (requestGeneration != generation) return
        val parent = when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                mutableState.value = MediaDetailUiState.Error(result.error)
                return
            }
        }
        val focusedChildId = parent.children.firstOrNull()?.id
        val childResult = focusedChildId?.let { requestChildDetail(session, it) }
        if (requestGeneration != generation) return
        val content = MediaDetailUiState.Content(parent = parent, focusedChildId = focusedChildId)
        mutableState.value = when (childResult) {
            is AppResult.Success -> {
                childDetailCache[childResult.value.id] = childResult.value
                content.copy(focusedChildDetail = childResult.value)
            }
            is AppResult.Failure -> content.copy(childDetailError = childResult.error)
            null -> content
        }
    }

    fun rememberFocusedChild(childId: Long) {
        val content = mutableState.value as? MediaDetailUiState.Content ?: return
        if (content.parent.children.none { it.id == childId }) return

        val cachedDetail = childDetailCache[childId]
        mutableState.value = content.copy(
            focusedChildId = childId,
            focusedChildDetail = cachedDetail,
            childDetailError = null,
        )
    }

    suspend fun loadFocusedChildAndNeighbors(
        session: Session,
        childId: Long,
    ) {
        val content = mutableState.value as? MediaDetailUiState.Content ?: return
        val focusedIndex = content.parent.children.indexOfFirst { it.id == childId }
        if (focusedIndex < 0 || content.focusedChildId != childId) return
        val requestGeneration = generation

        // Prefetch only adjacent episodes, and always load the selection first.
        for (index in listOf(focusedIndex, focusedIndex + 1, focusedIndex - 1)) {
            val current = mutableState.value as? MediaDetailUiState.Content ?: return
            if (
                requestGeneration != generation ||
                current.focusedChildId != childId ||
                current.childDetailError == AppError.Unauthorized
            ) {
                return
            }
            if (index == focusedIndex && current.childDetailError != null) continue
            val child = content.parent.children.getOrNull(index) ?: continue
            loadChildDetail(session, child.id)
        }
    }

    private suspend fun loadChildDetail(
        session: Session,
        childId: Long,
    ) {
        val content = mutableState.value as? MediaDetailUiState.Content ?: return
        if (content.parent.children.none { it.id == childId }) return
        val parentId = content.parent.id
        val requestGeneration = generation
        childDetailCache[childId]?.let { cachedDetail ->
            publishChildResult(parentId, childId) { current ->
                current.copy(
                    focusedChildDetail = cachedDetail,
                    childDetailError = null,
                )
            }
            return
        }

        val result = requestChildDetail(session, childId)
        if (requestGeneration != generation) return
        when (result) {
            is AppResult.Success -> {
                val current = mutableState.value as? MediaDetailUiState.Content ?: return
                if (
                    current.parent.id != parentId ||
                    current.parent.children.none { it.id == childId }
                ) {
                    return
                }
                childDetailCache[childId] = result.value
                if (current.focusedChildId == childId) {
                    mutableState.value = current.copy(
                        focusedChildDetail = result.value,
                        childDetailError = null,
                    )
                }
            }

            is AppResult.Failure -> publishChildFailure(
                parentId = parentId,
                childId = childId,
                error = result.error,
            )
        }
    }

    private suspend fun requestChildDetail(
        session: Session,
        childId: Long,
    ): AppResult<MediaDetail> {
        val result = repository.getMediaDetail(session, childId)
        currentCoroutineContext().ensureActive()
        return if (result is AppResult.Success && result.value.id != childId) {
            AppResult.Failure(AppError.InvalidData("media child detail"))
        } else {
            result
        }
    }

    fun rememberChildViewport(snapshot: GridViewportSnapshot) {
        val content = mutableState.value as? MediaDetailUiState.Content ?: return
        if (content.childViewport != snapshot) {
            mutableState.value = content.copy(childViewport = snapshot)
        }
    }

    private inline fun publishChildResult(
        parentId: Long,
        childId: Long,
        transform: (MediaDetailUiState.Content) -> MediaDetailUiState.Content,
    ) {
        val current = mutableState.value as? MediaDetailUiState.Content ?: return
        if (current.parent.id == parentId && current.focusedChildId == childId) {
            mutableState.value = transform(current)
        }
    }

    private fun publishChildFailure(
        parentId: Long,
        childId: Long,
        error: AppError,
    ) {
        val current = mutableState.value as? MediaDetailUiState.Content ?: return
        if (current.parent.id != parentId) return
        if (current.focusedChildId == childId) {
            mutableState.value = current.copy(
                focusedChildDetail = null,
                childDetailError = error,
            )
        } else if (error == AppError.Unauthorized) {
            mutableState.value = current.copy(childDetailError = error)
        }
    }
}
