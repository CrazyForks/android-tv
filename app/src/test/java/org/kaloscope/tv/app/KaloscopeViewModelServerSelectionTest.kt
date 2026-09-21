package org.kaloscope.tv.app

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.app.bootstrap.BootstrapState
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository
import org.kaloscope.tv.data.server.ServerConnectionInfo
import org.kaloscope.tv.data.server.ServerRepository

@OptIn(ExperimentalCoroutinesApi::class)
class KaloscopeViewModelServerSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val firstServer = selectionServer("first")
    private val secondServer = selectionServer("second")
    private lateinit var data: SelectionData
    private lateinit var viewModel: KaloscopeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        data = SelectionData(listOf(firstServer, secondServer))
        viewModel = KaloscopeViewModel(
            serverStore = data,
            serverRepository = data,
            sessionRepository = data,
            formDefaults = AppFormDefaults(),
        )
        dispatcher.scheduler.runCurrent()
        viewModel.showServerSelection()
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        data.pendingActivations.values.forEach { it.result.cancel() }
        data.pendingTokenReads.values.forEach { it.result.cancel() }
        data.pendingValidations.values.forEach { it.result.cancel() }
        viewModel.showServerSelection()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting a server without a token opens its login`() = runTest(dispatcher) {
        viewModel.selectServer(secondServer)
        runCurrent()

        assertEquals(BootstrapState.NeedsLogin(secondServer), viewModel.bootstrapState.value)
        assertEquals(secondServer.id, data.activeId)
        assertTrue(data.validatedServerIds.isEmpty())
    }

    @Test
    fun `selecting a saved session validates the selected server`() = runTest(dispatcher) {
        data.tokens[secondServer.id] = "second-token"

        viewModel.selectServer(secondServer)
        runCurrent()

        assertEquals(
            BootstrapState.Ready(selectionSession(secondServer, "second-token")),
            viewModel.bootstrapState.value,
        )
        assertEquals(secondServer.id, data.activeId)
        assertEquals(listOf(secondServer.id), data.validatedServerIds)
    }

    @Test
    fun `new selection cancels pending server activation`() = runTest(dispatcher) {
        val activation = PendingSelectionResult<Unit>()
        data.pendingActivations[firstServer.id] = activation
        viewModel.selectServer(firstServer)
        runCurrent()

        viewModel.selectServer(secondServer)
        runCurrent()
        activation.result.complete(Unit)
        runCurrent()

        assertEquals(BootstrapState.NeedsLogin(secondServer), viewModel.bootstrapState.value)
        assertEquals(secondServer.id, data.activeId)
        assertTrue(activation.cancelled)
    }

    @Test
    fun `new selection cancels pending token read`() = runTest(dispatcher) {
        val tokenRead = PendingSelectionResult<String?>()
        data.pendingTokenReads[firstServer.id] = tokenRead
        viewModel.selectServer(firstServer)
        runCurrent()

        viewModel.selectServer(secondServer)
        runCurrent()
        tokenRead.result.complete(null)
        runCurrent()

        assertEquals(BootstrapState.NeedsLogin(secondServer), viewModel.bootstrapState.value)
        assertEquals(secondServer.id, data.activeId)
        assertTrue(tokenRead.cancelled)
    }

    @Test
    fun `new selection cancels validation started by the previous selection`() =
        runTest(dispatcher) {
            val validation = PendingSelectionResult<AppResult<Session>>()
            data.tokens[firstServer.id] = "first-token"
            data.tokens[secondServer.id] = "second-token"
            data.pendingValidations[firstServer.id] = validation
            viewModel.selectServer(firstServer)
            runCurrent()
            assertEquals(BootstrapState.Loading, viewModel.bootstrapState.value)

            viewModel.selectServer(secondServer)
            runCurrent()
            validation.result.complete(
                AppResult.Success(selectionSession(firstServer, "first-token")),
            )
            runCurrent()

            assertEquals(
                BootstrapState.Ready(selectionSession(secondServer, "second-token")),
                viewModel.bootstrapState.value,
            )
            assertEquals(secondServer.id, data.activeId)
            assertTrue(validation.cancelled)
        }

    @Test
    fun `returning to server selection cancels pending validation`() = runTest(dispatcher) {
        val validation = PendingSelectionResult<AppResult<Session>>()
        data.tokens[firstServer.id] = "first-token"
        data.pendingValidations[firstServer.id] = validation
        viewModel.selectServer(firstServer)
        runCurrent()

        viewModel.showServerSelection()
        runCurrent()
        validation.result.complete(
            AppResult.Success(selectionSession(firstServer, "first-token")),
        )
        runCurrent()

        assertEquals(
            BootstrapState.NeedsServer(listOf(firstServer, secondServer)),
            viewModel.bootstrapState.value,
        )
        assertTrue(validation.cancelled)
    }

    @Test
    fun `retry replaces the previous bootstrap validation`() = runTest(dispatcher) {
        val previousValidation = PendingSelectionResult<AppResult<Session>>()
        data.tokens[firstServer.id] = "first-token"
        data.pendingValidations[firstServer.id] = previousValidation
        viewModel.selectServer(firstServer)
        runCurrent()

        val retryValidation = PendingSelectionResult<AppResult<Session>>()
        data.pendingValidations[firstServer.id] = retryValidation
        viewModel.retryBootstrap()
        runCurrent()
        retryValidation.result.complete(AppResult.Failure(AppError.Offline))
        runCurrent()
        previousValidation.result.complete(
            AppResult.Success(selectionSession(firstServer, "first-token")),
        )
        runCurrent()

        assertEquals(
            BootstrapState.ConnectionError(firstServer, AppError.Offline),
            viewModel.bootstrapState.value,
        )
        assertEquals("first-token", data.tokens[firstServer.id])
        assertTrue(previousValidation.cancelled)
    }
}

private class SelectionData(
    private val servers: List<SavedServer>,
) : ServerStore, ServerRepository, SessionRepository {
    var activeId: String? = servers.first().id
    val tokens = mutableMapOf<String, String>()
    val validatedServerIds = mutableListOf<String>()
    val pendingActivations = mutableMapOf<String, PendingSelectionResult<Unit>>()
    val pendingTokenReads = mutableMapOf<String, PendingSelectionResult<String?>>()
    val pendingValidations = mutableMapOf<String, PendingSelectionResult<AppResult<Session>>>()

    override suspend fun getServers(): List<SavedServer> = servers

    override suspend fun getActiveServerId(): String? = activeId

    override suspend fun setActiveServer(serverId: String) {
        pendingActivations[serverId]?.await()
        activeId = serverId
    }

    override suspend fun getToken(serverId: String): String? {
        pendingTokenReads[serverId]?.let { return it.await() }
        return tokens[serverId]
    }

    override suspend fun validate(server: SavedServer, token: String): AppResult<Session> {
        validatedServerIds += server.id
        pendingValidations[server.id]?.let { return it.await() }
        return AppResult.Success(selectionSession(server, token))
    }

    override suspend fun clearToken(serverId: String) {
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

private class PendingSelectionResult<T> {
    val result = CompletableDeferred<T>()
    var cancelled = false

    suspend fun await(): T = try {
        result.await()
    } catch (error: CancellationException) {
        cancelled = true
        throw error
    }
}

private fun selectionServer(id: String) = SavedServer(
    id = id,
    name = "Server $id",
    origin = "https://$id.example",
)

private fun selectionSession(server: SavedServer, token: String) = Session(
    server = server,
    token = token,
    user = SessionUser(id = 1, username = "tv_user", role = "user"),
)
