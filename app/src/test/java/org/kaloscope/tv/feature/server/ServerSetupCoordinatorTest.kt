package org.kaloscope.tv.feature.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.data.server.ServerConnectionInfo
import org.kaloscope.tv.data.server.ServerRepository

class ServerSetupCoordinatorTest {
    @Test
    fun `configured defaults initialize and reset server draft`() {
        val coordinator = coordinator(
            repository = FakeServerRepository(),
            initialName = "Debug",
            initialUrl = "https://debug.example",
        )

        assertEquals("Debug", coordinator.state.value.name)
        assertEquals("https://debug.example", coordinator.state.value.url)

        coordinator.updateName("Edited")
        coordinator.updateUrl("https://edited.example")
        coordinator.reset()

        assertEquals("Debug", coordinator.state.value.name)
        assertEquals("https://debug.example", coordinator.state.value.url)
    }

    @Test
    fun `invalid url does not call the server`() = runBlocking {
        val repository = FakeServerRepository()
        val coordinator = coordinator(repository)
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl("192.168.1.2:8000")

        coordinator.testConnection()

        assertEquals(ServerSetupError.InvalidUrl, coordinator.state.value.error)
        assertEquals(0, repository.testCalls)
        assertFalse(coordinator.state.value.canSave)
    }

    @Test
    fun `test in progress ignores concurrent retry`() = runBlocking {
        val repository = SuspendingServerRepository()
        val coordinator = coordinator(repository)
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl("http://192.168.1.2:8000")

        val firstTest = launch { coordinator.testConnection() }
        repository.firstCallStarted.await()
        val retry = launch { coordinator.testConnection() }
        yield()

        assertEquals(1, repository.testCalls)
        repository.release.complete(Unit)
        firstTest.join()
        retry.join()
    }

    @Test
    fun `successful test verifies the normalized origin`() = runBlocking {
        val repository = FakeServerRepository(testResult = AppResult.Success("0.5.3"))
        val coordinator = coordinator(repository)
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl(" http://192.168.1.2:8000/ ")

        coordinator.testConnection()

        assertEquals("http://192.168.1.2:8000", coordinator.state.value.verifiedOrigin)
        assertEquals("0.5.3", coordinator.state.value.serverVersion)
        assertTrue(coordinator.state.value.canSave)
    }

    @Test
    fun `http upgrade replaces the draft and saved origin`() = runBlocking {
        val repository = UpgradingServerRepository()
        val coordinator = coordinator(repository)
        coordinator.updateName("Demo")
        coordinator.updateUrl("http://demo.example")

        coordinator.testConnection()

        assertEquals("https://demo.example", coordinator.state.value.url)
        assertEquals("https://demo.example", coordinator.state.value.verifiedOrigin)
        assertEquals("0.8.7", coordinator.state.value.serverVersion)

        val saved = coordinator.save()

        assertEquals("https://demo.example", saved?.origin)
        assertEquals(saved, repository.savedServer)
    }

    @Test
    fun `successful test fills a blank name from the server host`() = runBlocking {
        val repository = FakeServerRepository(testResult = AppResult.Success("0.5.3"))
        val coordinator = coordinator(repository)
        coordinator.updateUrl(" https://Media.Example:8443/ ")

        coordinator.testConnection()

        assertEquals("media.example", coordinator.state.value.name)
        assertEquals("https://media.example:8443", coordinator.state.value.verifiedOrigin)
        assertTrue(coordinator.state.value.canSave)
    }

    @Test
    fun `editing url after testing invalidates connection proof`() = runBlocking {
        val coordinator = coordinator(FakeServerRepository())
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl("http://192.168.1.2:8000")
        coordinator.testConnection()

        coordinator.updateUrl("http://192.168.1.3:8000")

        assertNull(coordinator.state.value.verifiedOrigin)
        assertFalse(coordinator.state.value.canSave)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `editing url during testing discards the previous connection result`() = runTest {
        for (result in listOf(
            AppResult.Success(ServerConnectionInfo("https://old.example", "0.8.7")),
            AppResult.Failure(AppError.Timeout),
        )) {
            val repository = PendingServerRepository()
            val coordinator = coordinator(repository)
            coordinator.updateName("Demo")
            coordinator.updateUrl("https://old.example")
            val connection = launch { coordinator.testConnection() }
            runCurrent()

            coordinator.updateUrl("https://new.example")
            repository.results.single().complete(result)
            connection.join()

            assertEquals(
                ServerSetupState(name = "Demo", url = "https://new.example"),
                coordinator.state.value,
            )
            assertNull(coordinator.save())
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `reset discards a pending result even when the default url is unchanged`() = runTest {
        val repository = PendingServerRepository()
        val coordinator = coordinator(repository, initialUrl = "https://demo.example")
        val connection = launch { coordinator.testConnection() }
        runCurrent()

        coordinator.reset()
        repository.results.single().complete(
            AppResult.Success(ServerConnectionInfo("https://demo.example", "0.8.7")),
        )
        connection.join()

        assertEquals(ServerSetupState(url = "https://demo.example"), coordinator.state.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `an old failure cannot clear a newer connection proof`() = runTest {
        val repository = PendingServerRepository()
        val coordinator = coordinator(repository)
        coordinator.updateUrl("https://old.example")
        val oldConnection = launch { coordinator.testConnection() }
        runCurrent()

        coordinator.reset()
        coordinator.updateUrl("https://new.example")
        val newConnection = launch { coordinator.testConnection() }
        runCurrent()
        coordinator.updateName("Edited while testing")
        repository.results.last().complete(
            AppResult.Success(ServerConnectionInfo("https://new.example", "0.8.7")),
        )
        newConnection.join()
        val verifiedState = coordinator.state.value
        assertEquals("Edited while testing", verifiedState.name)
        assertTrue(verifiedState.canSave)

        repository.results.first().complete(AppResult.Failure(AppError.Timeout))
        oldConnection.join()

        assertEquals(verifiedState, coordinator.state.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelling an old test preserves the new loading state and cancellation propagates`() =
        runTest {
            val repository = PendingServerRepository()
            val coordinator = coordinator(repository)
            coordinator.updateUrl("https://old.example")
            val oldConnection = launch { coordinator.testConnection() }
            runCurrent()

            coordinator.reset()
            coordinator.updateUrl("https://new.example")
            var newConnectionReturned = false
            val newConnection = launch {
                coordinator.testConnection()
                newConnectionReturned = true
            }
            runCurrent()

            oldConnection.cancelAndJoin()
            val newConnectionStillTesting = coordinator.state.value.isTesting
            newConnection.cancelAndJoin()

            assertTrue(newConnectionStillTesting)
            assertFalse(newConnectionReturned)
            assertFalse(coordinator.state.value.isTesting)
            assertNull(coordinator.state.value.error)

            val retry = launch { coordinator.testConnection() }
            runCurrent()
            repository.results.last().complete(
                AppResult.Success(ServerConnectionInfo("https://new.example", "0.8.7")),
            )
            retry.join()
            assertTrue(coordinator.state.value.canSave)
        }

    @Test
    fun `save persists and activates only the verified server`() = runBlocking {
        val repository = FakeServerRepository()
        val coordinator = coordinator(repository)
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl("http://192.168.1.2:8000/")
        coordinator.testConnection()

        val saved = coordinator.save()

        assertEquals(
            SavedServer(
                id = "generated-id",
                name = "家庭服务器",
                origin = "http://192.168.1.2:8000",
            ),
            saved,
        )
        assertEquals(saved, repository.savedServer)
        assertEquals("generated-id", repository.activeServerId)
    }

    @Test
    fun `connection failure keeps the draft and exposes recoverable error`() = runBlocking {
        val repository = FakeServerRepository(
            testResult = AppResult.Failure(AppError.Timeout),
        )
        val coordinator = coordinator(repository)
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl("http://192.168.1.2:8000")

        coordinator.testConnection()

        assertEquals("家庭服务器", coordinator.state.value.name)
        assertEquals("http://192.168.1.2:8000", coordinator.state.value.url)
        assertEquals(ServerSetupError.Connection(AppError.Timeout), coordinator.state.value.error)
        assertFalse(coordinator.state.value.canSave)
    }

    @Test
    fun `storage failure restores save state and exposes error`() = runBlocking {
        val repository = FakeServerRepository(failSave = true)
        val coordinator = coordinator(repository)
        coordinator.updateName("家庭服务器")
        coordinator.updateUrl("http://192.168.1.2:8000")
        coordinator.testConnection()

        val saved = coordinator.save()

        assertNull(saved)
        assertEquals(ServerSetupError.SaveFailed, coordinator.state.value.error)
        assertFalse(coordinator.state.value.isSaving)
    }
}

private class FakeServerRepository(
    private val testResult: AppResult<String> = AppResult.Success("0.0.0"),
    private val failSave: Boolean = false,
) : ServerRepository {
    var testCalls = 0
    var savedServer: SavedServer? = null
    var activeServerId: String? = null

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> {
        testCalls += 1
        return when (testResult) {
            is AppResult.Success -> AppResult.Success(
                ServerConnectionInfo(origin = origin, version = testResult.value),
            )

            is AppResult.Failure -> testResult
        }
    }

    override suspend fun saveServer(server: SavedServer) {
        if (failSave) {
            error("storage unavailable")
        }
        savedServer = server
    }

    override suspend fun deleteServer(serverId: String): List<SavedServer> =
        error("Not used")

    override suspend fun setActiveServer(serverId: String) {
        activeServerId = serverId
    }
}

private class SuspendingServerRepository : ServerRepository {
    val firstCallStarted = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var testCalls = 0

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> {
        testCalls += 1
        firstCallStarted.complete(Unit)
        release.await()
        return AppResult.Success(ServerConnectionInfo(origin = origin, version = "0.0.0"))
    }

    override suspend fun saveServer(server: SavedServer) = Unit

    override suspend fun deleteServer(serverId: String): List<SavedServer> =
        error("Not used")

    override suspend fun setActiveServer(serverId: String) = Unit
}

private class PendingServerRepository : ServerRepository {
    val results = mutableListOf<CompletableDeferred<AppResult<ServerConnectionInfo>>>()

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> =
        CompletableDeferred<AppResult<ServerConnectionInfo>>().also(results::add).await()

    override suspend fun saveServer(server: SavedServer) = error("Not used")

    override suspend fun deleteServer(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun setActiveServer(serverId: String) = error("Not used")
}

private class UpgradingServerRepository : ServerRepository {
    var savedServer: SavedServer? = null

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> =
        AppResult.Success(
            ServerConnectionInfo(
                origin = "https://demo.example",
                version = "0.8.7",
            ),
        )

    override suspend fun saveServer(server: SavedServer) {
        savedServer = server
    }

    override suspend fun deleteServer(serverId: String): List<SavedServer> =
        error("Not used")

    override suspend fun setActiveServer(serverId: String) = Unit
}

private fun coordinator(
    repository: ServerRepository,
    initialName: String = "",
    initialUrl: String = "",
) = ServerSetupCoordinator(
    repository = repository,
    createServerId = { "generated-id" },
    initialName = initialName,
    initialUrl = initialUrl,
)
