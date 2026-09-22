package org.kaloscope.tv.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.kaloscope.tv.app.bootstrap.BootstrapCoordinator
import org.kaloscope.tv.app.bootstrap.BootstrapState
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository
import org.kaloscope.tv.data.server.ServerRepository
import org.kaloscope.tv.feature.login.LoginCoordinator
import org.kaloscope.tv.feature.login.LoginState
import org.kaloscope.tv.feature.server.SavedServerDeletionCoordinator
import org.kaloscope.tv.feature.server.SavedServerDeletionState
import org.kaloscope.tv.feature.server.ServerSetupCoordinator
import org.kaloscope.tv.feature.server.ServerSetupState

@HiltViewModel
class KaloscopeViewModel @Inject constructor(
    private val serverStore: ServerStore,
    private val serverRepository: ServerRepository,
    private val sessionRepository: SessionRepository,
    private val formDefaults: AppFormDefaults,
) : ViewModel() {
    private val bootstrapCoordinator = BootstrapCoordinator(serverStore, sessionRepository)
    private val serverCoordinator = ServerSetupCoordinator(
        repository = serverRepository,
        createServerId = { UUID.randomUUID().toString() },
        initialName = formDefaults.serverName,
        initialUrl = formDefaults.serverUrl,
    )
    private val serverDeletionCoordinator = SavedServerDeletionCoordinator(
        serverRepository = serverRepository,
        sessionRepository = sessionRepository,
    )
    private val mutableBootstrapState =
        MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    private val mutableLoginState = MutableStateFlow(LoginState())

    private var loginCoordinator: LoginCoordinator? = null
    private var loginStateJob: Job? = null
    private var serverConnectionJob: Job? = null
    private var bootstrapJob: Job? = null

    val bootstrapState: StateFlow<BootstrapState> = mutableBootstrapState.asStateFlow()
    val serverSetupState: StateFlow<ServerSetupState> = serverCoordinator.state
    val serverDeletionState: StateFlow<SavedServerDeletionState> =
        serverDeletionCoordinator.state
    val loginState: StateFlow<LoginState> = mutableLoginState.asStateFlow()

    init {
        retryBootstrap()
    }

    fun updateServerName(value: String) = serverCoordinator.updateName(value)

    fun updateServerUrl(value: String) {
        serverConnectionJob?.cancel()
        serverConnectionJob = null
        serverCoordinator.updateUrl(value)
    }

    fun testServerConnection() {
        if (serverConnectionJob?.isActive == true) {
            return
        }
        serverConnectionJob = viewModelScope.launch {
            serverCoordinator.testConnection()
        }
    }

    fun saveServer() {
        viewModelScope.launch {
            serverCoordinator.save()?.let(::showLogin)
        }
    }

    fun showServerSelection() {
        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch {
            // Drop any password-bearing login state before showing another root screen.
            stopLoginCollection()
            serverConnectionJob?.cancel()
            serverConnectionJob = null
            serverCoordinator.reset()
            serverDeletionCoordinator.clearError()
            try {
                val servers = serverStore.getServers()
                currentCoroutineContext().ensureActive()
                mutableBootstrapState.value = BootstrapState.NeedsServer(servers)
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                mutableBootstrapState.value = BootstrapState.ServerListError
            }
        }
    }

    fun deleteServer(server: SavedServer) {
        viewModelScope.launch {
            serverDeletionCoordinator.delete(server.id)?.let { remainingServers ->
                mutableBootstrapState.value = BootstrapState.NeedsServer(remainingServers)
            }
        }
    }

    fun clearServerDeletionError() = serverDeletionCoordinator.clearError()

    fun selectServer(server: SavedServer) {
        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch {
            try {
                serverRepository.setActiveServer(server.id)
                currentCoroutineContext().ensureActive()
                // Tokens are isolated by server ID and never reused across origins.
                val token = sessionRepository.getToken(server.id)
                currentCoroutineContext().ensureActive()
                if (token.isNullOrBlank()) {
                    showLogin(server)
                } else {
                    // Validation must remain part of the selection's cancellable job.
                    resolveBootstrap()
                }
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                // Retry the requested server even when persisting the active ID failed.
                mutableBootstrapState.value = BootstrapState.ServerSelectionError(server)
            }
        }
    }

    fun updateUsername(value: String) {
        loginCoordinator?.updateUsername(value)
    }

    fun updatePassword(value: String) {
        loginCoordinator?.updatePassword(value)
    }

    fun submitLogin() {
        val coordinator = loginCoordinator ?: return
        viewModelScope.launch {
            coordinator.submit()?.let { session ->
                // Replacing the root state removes the entire login subtree from composition.
                mutableBootstrapState.value = BootstrapState.Ready(session)
                stopLoginCollection()
            }
        }
    }

    fun retryBootstrap() {
        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch {
            resolveBootstrap()
        }
    }

    fun useDifferentAccount(server: SavedServer) {
        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch {
            mutableBootstrapState.value = BootstrapState.Loading
            try {
                sessionRepository.clearToken(server.id)
                currentCoroutineContext().ensureActive()
                showLogin(server)
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                // Retrying bootstrap could restore the token that failed to clear.
                mutableBootstrapState.value = BootstrapState.SessionClearError(server)
            }
        }
    }

    fun logout() {
        val ready = mutableBootstrapState.value as? BootstrapState.Ready ?: return
        useDifferentAccount(ready.session.server)
    }

    fun handleUnauthorized(session: Session) {
        val ready = mutableBootstrapState.value as? BootstrapState.Ready ?: return
        if (ready.session.server.id != session.server.id) {
            return
        }
        useDifferentAccount(session.server)
    }

    private suspend fun resolveBootstrap() {
        mutableBootstrapState.value = BootstrapState.Loading
        val resolved = bootstrapCoordinator.resolve()
        currentCoroutineContext().ensureActive()
        if (resolved is BootstrapState.NeedsLogin) {
            showLogin(resolved.server)
        } else {
            mutableBootstrapState.value = resolved
        }
    }

    private fun showLogin(server: SavedServer) {
        // A coordinator is bound to one server, so switching servers must replace it.
        stopLoginCollection()
        val coordinator = LoginCoordinator(
            server = server,
            repository = sessionRepository,
            initialUsername = formDefaults.username,
            initialPassword = formDefaults.password,
        )
        loginCoordinator = coordinator
        mutableLoginState.value = coordinator.state.value
        loginStateJob = viewModelScope.launch {
            coordinator.state.collect { mutableLoginState.value = it }
        }
        mutableBootstrapState.value = BootstrapState.NeedsLogin(server)
    }

    private fun stopLoginCollection() {
        loginStateJob?.cancel()
        loginStateJob = null
        loginCoordinator = null
        // Resetting the state also removes any password still held in memory.
        mutableLoginState.value = LoginState()
    }
}
