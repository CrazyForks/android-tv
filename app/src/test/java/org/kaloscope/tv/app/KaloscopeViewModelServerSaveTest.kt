package org.kaloscope.tv.app

import androidx.lifecycle.viewModelScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.app.bootstrap.BootstrapState
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository
import org.kaloscope.tv.data.server.ServerConnectionInfo
import org.kaloscope.tv.data.server.ServerRepository

@OptIn(ExperimentalCoroutinesApi::class)
class KaloscopeViewModelServerSaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val existingServer = SavedServer("existing", "Existing", "https://existing.example")
    private lateinit var data: SaveData
    private lateinit var viewModel: KaloscopeViewModel
    private lateinit var completion: CompletableDeferred<Unit>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        data = SaveData(existingServer)
        completion = CompletableDeferred()
        viewModel = KaloscopeViewModel(
            serverStore = data,
            serverRepository = data,
            sessionRepository = data,
            formDefaults = AppFormDefaults(),
        )
        dispatcher.scheduler.runCurrent()
        viewModel.showServerSelection()
        dispatcher.scheduler.runCurrent()
        viewModel.updateServerName("New server")
        viewModel.updateServerUrl("https://new.example")
        viewModel.testServerConnection()
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        completion.cancel()
        viewModel.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `repeated save keeps the pending operation and opens the saved server login once`() =
        runTest(dispatcher) {
            data.beforeSave = { completion.await() }
            viewModel.saveServer()
            runCurrent()

            viewModel.saveServer()
            runCurrent()

            assertTrue(viewModel.serverSetupState.value.isSaving)
            val saved = data.saveAttempts.single()
            assertTrue(data.activationAttempts.isEmpty())
            completion.complete(Unit)
            runCurrent()

            assertEquals(BootstrapState.NeedsLogin(saved), viewModel.bootstrapState.value)
            assertEquals(listOf(saved.id), data.activationAttempts)
            assertEquals(listOf(existingServer, saved), data.servers)
            assertFalse(viewModel.serverSetupState.value.isSaving)
        }

    @Test
    fun `selecting an existing server cancels the pending save`() = runTest(dispatcher) {
        var cancelled = false
        data.beforeSave = {
            try {
                completion.await()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        viewModel.saveServer()
        runCurrent()

        viewModel.selectServer(existingServer)
        runCurrent()

        assertTrue(cancelled)
        assertFalse(viewModel.serverSetupState.value.isSaving)
        assertEquals(BootstrapState.NeedsLogin(existingServer), viewModel.bootstrapState.value)
        assertEquals(listOf(existingServer.id), data.activationAttempts)
        assertEquals(listOf(existingServer), data.servers)
    }

    @Test
    fun `late save completion cannot activate its server or replace the selected login`() =
        runTest(dispatcher) {
            data.beforeSave = { withContext(NonCancellable) { completion.await() } }
            viewModel.saveServer()
            runCurrent()

            viewModel.selectServer(existingServer)
            runCurrent()
            completion.complete(Unit)
            runCurrent()

            assertEquals(BootstrapState.NeedsLogin(existingServer), viewModel.bootstrapState.value)
            assertEquals(existingServer.id, data.activeId)
            assertEquals(listOf(existingServer.id), data.activationAttempts)
            assertFalse(viewModel.serverSetupState.value.isSaving)
        }

    @Test
    fun `late activation completion cannot replace the selected login`() = runTest(dispatcher) {
        data.afterActivation = { serverId ->
            if (serverId != existingServer.id) {
                withContext(NonCancellable) { completion.await() }
            }
        }
        viewModel.saveServer()
        runCurrent()
        val saved = data.saveAttempts.single()
        assertEquals(saved.id, data.activeId)

        viewModel.selectServer(existingServer)
        runCurrent()
        completion.complete(Unit)
        runCurrent()

        assertEquals(BootstrapState.NeedsLogin(existingServer), viewModel.bootstrapState.value)
        assertEquals(existingServer.id, data.activeId)
        assertEquals(listOf(saved.id, existingServer.id), data.activationAttempts)
        assertFalse(viewModel.serverSetupState.value.isSaving)
    }
}

private class SaveData(existingServer: SavedServer) :
    ServerStore,
    ServerRepository,
    SessionRepository {
    val servers = mutableListOf(existingServer)
    var activeId = existingServer.id
    val saveAttempts = mutableListOf<SavedServer>()
    val activationAttempts = mutableListOf<String>()
    var beforeSave: suspend () -> Unit = {}
    var afterActivation: suspend (String) -> Unit = {}

    override suspend fun getServers(): List<SavedServer> = servers.toList()

    override suspend fun getActiveServerId(): String = activeId

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> =
        AppResult.Success(ServerConnectionInfo(origin, "0.8.7"))

    override suspend fun saveServer(server: SavedServer) {
        saveAttempts += server
        beforeSave()
        servers.removeAll { it.id == server.id }
        servers += server
    }

    override suspend fun setActiveServer(serverId: String) {
        activationAttempts += serverId
        activeId = serverId
        afterActivation(serverId)
    }

    override suspend fun getToken(serverId: String): String? = null

    override suspend fun save(server: SavedServer) = error("Not used")

    override suspend fun delete(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun setActiveServerId(serverId: String) = error("Not used")

    override suspend fun deleteServer(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun login(
        server: SavedServer,
        username: String,
        password: String,
    ): AppResult<Session> = error("Not used")

    override suspend fun validate(server: SavedServer, token: String): AppResult<Session> =
        error("Not used")

    override suspend fun clearToken(serverId: String) = error("Not used")
}
