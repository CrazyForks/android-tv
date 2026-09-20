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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository
import org.kaloscope.tv.data.server.ServerConnectionInfo
import org.kaloscope.tv.data.server.ServerRepository
import org.kaloscope.tv.feature.server.ServerSetupState

@OptIn(ExperimentalCoroutinesApi::class)
class KaloscopeViewModelServerConnectionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PendingConnectionRepository
    private lateinit var viewModel: KaloscopeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = PendingConnectionRepository()
        viewModel = KaloscopeViewModel(
            serverStore = ConnectionServerStore(),
            serverRepository = repository,
            sessionRepository = ConnectionSessionRepository(),
            formDefaults = AppFormDefaults(),
        )
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        repository.requests.forEach { it.result.cancel() }
        viewModel.showServerSelection()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `editing the address cancels its test and allows testing the new address`() =
        runTest(dispatcher) {
            viewModel.updateServerUrl("https://old.example")
            viewModel.testServerConnection()
            runCurrent()
            viewModel.testServerConnection()
            runCurrent()
            val oldRequest = repository.requests.single()
            assertFalse(oldRequest.cancelled)

            viewModel.updateServerUrl("https://new.example")
            viewModel.testServerConnection()
            runCurrent()

            assertTrue(oldRequest.cancelled)
            assertEquals(
                listOf("https://old.example", "https://new.example"),
                repository.requests.map { it.origin },
            )
            assertTrue(viewModel.serverSetupState.value.isTesting)
            repository.requests.last().result.complete(
                AppResult.Success(ServerConnectionInfo("https://new.example", "0.8.7")),
            )
            runCurrent()

            assertEquals("https://new.example", viewModel.serverSetupState.value.verifiedOrigin)
            assertTrue(viewModel.serverSetupState.value.canSave)
        }

    @Test
    fun `returning to server selection cancels the test before resetting the draft`() =
        runTest(dispatcher) {
            viewModel.updateServerUrl("https://old.example")
            viewModel.testServerConnection()
            runCurrent()
            val request = repository.requests.single()

            viewModel.showServerSelection()
            runCurrent()
            request.result.complete(
                AppResult.Success(ServerConnectionInfo("https://old.example", "0.8.7")),
            )
            runCurrent()

            assertTrue(request.cancelled)
            assertEquals(ServerSetupState(), viewModel.serverSetupState.value)
        }
}

private class PendingConnectionRepository : ServerRepository {
    val requests = mutableListOf<PendingConnection>()

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> {
        val request = PendingConnection(origin)
        requests += request
        return try {
            request.result.await()
        } catch (error: CancellationException) {
            request.cancelled = true
            throw error
        }
    }

    override suspend fun saveServer(server: SavedServer) = error("Not used")

    override suspend fun deleteServer(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun setActiveServer(serverId: String) = error("Not used")
}

private class PendingConnection(val origin: String) {
    val result = CompletableDeferred<AppResult<ServerConnectionInfo>>()
    var cancelled = false
}

private class ConnectionServerStore : ServerStore {
    override suspend fun getServers(): List<SavedServer> = emptyList()

    override suspend fun getActiveServerId(): String? = null

    override suspend fun save(server: SavedServer) = error("Not used")

    override suspend fun delete(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun setActiveServerId(serverId: String) = error("Not used")
}

private class ConnectionSessionRepository : SessionRepository {
    override suspend fun login(
        server: SavedServer,
        username: String,
        password: String,
    ): AppResult<Session> = error("Not used")

    override suspend fun validate(
        server: SavedServer,
        token: String,
    ): AppResult<Session> = error("Not used")

    override suspend fun getToken(serverId: String): String? = null

    override suspend fun clearToken(serverId: String) = error("Not used")
}
