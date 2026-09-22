package org.kaloscope.tv.app

import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.app.bootstrap.BootstrapState
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository
import org.kaloscope.tv.data.server.ServerConnectionInfo
import org.kaloscope.tv.data.server.ServerRepository

@OptIn(ExperimentalCoroutinesApi::class)
class KaloscopeViewModelLogoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val firstServer = logoutServer("first")
    private val secondServer = logoutServer("second")
    private lateinit var data: LogoutData
    private lateinit var viewModel: KaloscopeViewModel
    private lateinit var clearResult: CompletableDeferred<Unit>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        clearResult = CompletableDeferred()
        data = LogoutData(listOf(firstServer, secondServer))
        viewModel = KaloscopeViewModel(
            serverStore = data,
            serverRepository = data,
            sessionRepository = data,
            formDefaults = AppFormDefaults(),
        )
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        clearResult.cancel()
        viewModel.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `logout blocks the session until its token is cleared and ignores repeated requests`() =
        runTest(dispatcher) {
            data.beforeClearToken = { clearResult.await() }
            val session = (viewModel.bootstrapState.value as BootstrapState.Ready).session

            viewModel.logout()
            runCurrent()

            assertEquals(BootstrapState.Loading, viewModel.bootstrapState.value)
            assertEquals("first-token", data.tokens[firstServer.id])
            viewModel.logout()
            viewModel.handleUnauthorized(session)
            runCurrent()
            assertEquals(listOf(firstServer.id), data.clearAttempts)

            clearResult.complete(Unit)
            runCurrent()

            assertEquals(BootstrapState.NeedsLogin(firstServer), viewModel.bootstrapState.value)
            assertNull(data.tokens[firstServer.id])
            assertEquals("second-token", data.tokens[secondServer.id])
            assertEquals(firstServer.id, data.activeId)
        }

    @Test
    fun `failed logout preserves tokens and retries clearing the same server without validation`() =
        runTest(dispatcher) {
            data.beforeClearToken = { throw IOException("Write failed") }

            viewModel.logout()
            runCurrent()

            assertEquals(
                BootstrapState.SessionClearError(firstServer),
                viewModel.bootstrapState.value,
            )
            assertEquals("first-token", data.tokens[firstServer.id])
            assertEquals("second-token", data.tokens[secondServer.id])

            val failure = viewModel.bootstrapState.value as BootstrapState.SessionClearError
            data.beforeClearToken = {}
            viewModel.useDifferentAccount(failure.server)
            runCurrent()

            assertEquals(BootstrapState.NeedsLogin(firstServer), viewModel.bootstrapState.value)
            assertNull(data.tokens[firstServer.id])
            assertEquals("second-token", data.tokens[secondServer.id])
            assertEquals(listOf(firstServer.id, firstServer.id), data.clearAttempts)
            assertEquals(listOf(firstServer.id), data.validatedServerIds)
        }

    @Test
    fun `unauthorized session leaves the shell even when clearing its token fails`() =
        runTest(dispatcher) {
            data.beforeClearToken = { throw IOException("Write failed") }
            val session = (viewModel.bootstrapState.value as BootstrapState.Ready).session

            viewModel.handleUnauthorized(session)
            runCurrent()

            assertEquals(
                BootstrapState.SessionClearError(firstServer),
                viewModel.bootstrapState.value,
            )
            assertEquals(listOf(firstServer.id), data.clearAttempts)
            assertEquals("first-token", data.tokens[firstServer.id])
            assertEquals("second-token", data.tokens[secondServer.id])
        }

    @Test
    fun `unauthorized result from another server does not clear the active session`() =
        runTest(dispatcher) {
            val initialState = viewModel.bootstrapState.value

            viewModel.handleUnauthorized(logoutSession(secondServer, "second-token"))
            runCurrent()

            assertEquals(initialState, viewModel.bootstrapState.value)
            assertTrue(data.clearAttempts.isEmpty())
            assertEquals("first-token", data.tokens[firstServer.id])
            assertEquals("second-token", data.tokens[secondServer.id])
        }

    @Test
    fun `selecting another server cancels pending logout`() = runTest(dispatcher) {
        var cancelled = false
        data.beforeClearToken = {
            try {
                clearResult.await()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        viewModel.logout()
        runCurrent()

        viewModel.selectServer(secondServer)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(
            BootstrapState.Ready(logoutSession(secondServer, "second-token")),
            viewModel.bootstrapState.value,
        )
        assertEquals("first-token", data.tokens[firstServer.id])
        assertEquals("second-token", data.tokens[secondServer.id])
    }

    @Test
    fun `late logout failure cannot replace a newer session`() = runTest(dispatcher) {
        data.beforeClearToken = { withContext(NonCancellable) { clearResult.await() } }
        viewModel.logout()
        runCurrent()

        viewModel.selectServer(secondServer)
        runCurrent()
        clearResult.completeExceptionally(IOException("Late write failure"))
        runCurrent()

        assertEquals(
            BootstrapState.Ready(logoutSession(secondServer, "second-token")),
            viewModel.bootstrapState.value,
        )
        assertEquals("first-token", data.tokens[firstServer.id])
        assertEquals("second-token", data.tokens[secondServer.id])
        assertEquals(secondServer.id, data.activeId)
    }

    @Test
    fun `late logout completion cannot replace a newer session`() = runTest(dispatcher) {
        data.beforeClearToken = { withContext(NonCancellable) { clearResult.await() } }
        viewModel.logout()
        runCurrent()

        viewModel.selectServer(secondServer)
        runCurrent()
        clearResult.complete(Unit)
        runCurrent()

        assertEquals(
            BootstrapState.Ready(logoutSession(secondServer, "second-token")),
            viewModel.bootstrapState.value,
        )
        assertNull(data.tokens[firstServer.id])
        assertEquals("second-token", data.tokens[secondServer.id])
        assertEquals(secondServer.id, data.activeId)
    }
}

private class LogoutData(
    private val servers: List<SavedServer>,
) : ServerStore, ServerRepository, SessionRepository {
    var activeId: String = servers.first().id
    val tokens = servers.associate { it.id to "${it.id}-token" }.toMutableMap()
    val clearAttempts = mutableListOf<String>()
    val validatedServerIds = mutableListOf<String>()
    var beforeClearToken: suspend () -> Unit = {}

    override suspend fun getServers(): List<SavedServer> = servers

    override suspend fun getActiveServerId(): String = activeId

    override suspend fun setActiveServer(serverId: String) {
        activeId = serverId
    }

    override suspend fun getToken(serverId: String): String? = tokens[serverId]

    override suspend fun validate(server: SavedServer, token: String): AppResult<Session> {
        validatedServerIds += server.id
        return AppResult.Success(logoutSession(server, token))
    }

    override suspend fun clearToken(serverId: String) {
        clearAttempts += serverId
        beforeClearToken()
        tokens.remove(serverId)
    }

    override suspend fun save(server: SavedServer) = error("Not used")

    override suspend fun delete(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun setActiveServerId(serverId: String) = error("Not used")

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> =
        error("Not used")

    override suspend fun saveServer(server: SavedServer) = error("Not used")

    override suspend fun deleteServer(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun login(
        server: SavedServer,
        username: String,
        password: String,
    ): AppResult<Session> = error("Not used")
}

private fun logoutServer(id: String) = SavedServer(
    id = id,
    name = "Server $id",
    origin = "https://$id.example",
)

private fun logoutSession(server: SavedServer, token: String) = Session(
    server = server,
    token = token,
    user = SessionUser(id = 1, username = "tv_user", role = "user"),
)
