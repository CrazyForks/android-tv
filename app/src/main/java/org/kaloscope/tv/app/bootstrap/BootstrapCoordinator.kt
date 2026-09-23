package org.kaloscope.tv.app.bootstrap

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository

class BootstrapCoordinator(
    private val serverStore: ServerStore,
    private val sessionRepository: SessionRepository,
) {
    suspend fun resolve(): BootstrapState {
        return try {
            val servers = serverStore.getServers()
            if (servers.isEmpty()) {
                return BootstrapState.NeedsServer(emptyList())
            }

            val activeServerId = serverStore.getActiveServerId()
            val activeServer = servers.firstOrNull { it.id == activeServerId } ?: servers.first()
            val token = sessionRepository.getToken(activeServer.id)
            if (token.isNullOrBlank()) {
                return BootstrapState.NeedsLogin(activeServer)
            }

            val result = sessionRepository.validate(activeServer, token)
            // A queued HTTP failure must not clear a token after validation is cancelled.
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Success -> BootstrapState.Ready(result.value)
                is AppResult.Failure -> {
                    // Only authentication failures invalidate a stored session.
                    if (result.error == AppError.Unauthorized) {
                        sessionRepository.clearToken(activeServer.id)
                        BootstrapState.NeedsLogin(activeServer)
                    } else {
                        BootstrapState.ConnectionError(activeServer, result.error)
                    }
                }
            }
        } catch (_: IOException) {
            // Keep unreadable server/session data intact so a retry can recover it.
            BootstrapState.StorageError
        }
    }
}
