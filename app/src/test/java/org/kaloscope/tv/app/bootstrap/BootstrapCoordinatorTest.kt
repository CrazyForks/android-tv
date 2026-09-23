package org.kaloscope.tv.app.bootstrap

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.network.networkCall
import org.kaloscope.tv.core.storage.ServerStore
import org.kaloscope.tv.data.auth.SessionRepository
import retrofit2.HttpException
import retrofit2.Response

class BootstrapCoordinatorTest {
    @Test
    fun `requires a server when none are saved`() = runBlocking {
        val data = FakeBootstrapData()

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.NeedsServer(emptyList()), state)
    }

    @Test
    fun `requires login when active server has no token`() = runBlocking {
        val server = savedServer()
        val data = FakeBootstrapData(servers = listOf(server))

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.NeedsLogin(server), state)
    }

    @Test
    fun `enters ready state after validating a saved session`() = runBlocking {
        val server = savedServer()
        val session = session(server)
        val data = FakeBootstrapData(
            servers = listOf(server),
            token = "saved-token",
            validation = AppResult.Success(session),
        )

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.Ready(session), state)
        assertFalse(data.tokenCleared)
    }

    @Test
    fun `clears only an unauthorized token and returns to login`() = runBlocking {
        val server = savedServer()
        val data = FakeBootstrapData(
            servers = listOf(server),
            token = "expired-token",
            validation = AppResult.Failure(AppError.Unauthorized),
        )

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.NeedsLogin(server), state)
        assertTrue(data.tokenCleared)
    }

    @Test
    fun `keeps token when validation fails because server is offline`() = runBlocking {
        val server = savedServer()
        val data = FakeBootstrapData(
            servers = listOf(server),
            token = "saved-token",
            validation = AppResult.Failure(AppError.Offline),
        )

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.ConnectionError(server, AppError.Offline), state)
        assertFalse(data.tokenCleared)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelled validation cannot clear a token from a queued authorization error`() = runTest {
        val response = PendingValidation()
        val data = FakeBootstrapData(
            servers = listOf(savedServer()),
            token = "saved-token",
            pendingValidation = response,
        )
        var resolved: BootstrapState? = null
        val job = launch { resolved = BootstrapCoordinator(data, data).resolve() }
        runCurrent()

        response.failUnauthorized()
        job.cancel()
        runCurrent()

        assertEquals(1, data.validationCount)
        assertFalse(data.tokenCleared)
        assertNull(resolved)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `active validation clears an unauthorized token from a queued error`() = runTest {
        val server = savedServer()
        val response = PendingValidation()
        val data = FakeBootstrapData(
            servers = listOf(server),
            token = "saved-token",
            pendingValidation = response,
        )
        val result = async { BootstrapCoordinator(data, data).resolve() }
        runCurrent()

        response.failUnauthorized()

        assertEquals(BootstrapState.NeedsLogin(server), result.await())
        assertEquals(1, data.validationCount)
        assertTrue(data.tokenCleared)
    }

    @Test
    fun `retries failed server reads without losing the saved session`() = runBlocking {
        val server = savedServer()
        val session = session(server)
        val data = FakeBootstrapData(
            servers = listOf(server),
            token = session.token,
            validation = AppResult.Success(session),
        )
        data.serverReadFailure = IOException("Read failed")
        val coordinator = BootstrapCoordinator(data, data)

        assertEquals(BootstrapState.StorageError, coordinator.resolve())
        assertEquals(0, data.validationCount)
        assertFalse(data.tokenCleared)

        data.serverReadFailure = null

        assertEquals(BootstrapState.Ready(session), coordinator.resolve())
        assertEquals(1, data.validationCount)
        assertFalse(data.tokenCleared)
    }

    @Test
    fun `reports storage error when active server cannot be read`() = runBlocking {
        val data = FakeBootstrapData(servers = listOf(savedServer()), token = "saved-token")
        data.activeServerReadFailure = IOException("Read failed")

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.StorageError, state)
        assertEquals(0, data.validationCount)
        assertFalse(data.tokenCleared)
    }

    @Test
    fun `reports storage error instead of login when token cannot be read`() = runBlocking {
        val data = FakeBootstrapData(servers = listOf(savedServer()), token = "saved-token")
        data.tokenReadFailure = IOException("Read failed")

        val state = BootstrapCoordinator(data, data).resolve()

        assertEquals(BootstrapState.StorageError, state)
        assertEquals(0, data.validationCount)
        assertFalse(data.tokenCleared)
    }

    @Test
    fun `retries failed removal of an unauthorized token before returning to login`() = runBlocking {
        val server = savedServer()
        val data = FakeBootstrapData(
            servers = listOf(server),
            token = "expired-token",
            validation = AppResult.Failure(AppError.Unauthorized),
        )
        data.tokenClearFailure = IOException("Write failed")
        val coordinator = BootstrapCoordinator(data, data)

        assertEquals(BootstrapState.StorageError, coordinator.resolve())
        assertFalse(data.tokenCleared)

        data.tokenClearFailure = null

        assertEquals(BootstrapState.NeedsLogin(server), coordinator.resolve())
        assertEquals(2, data.validationCount)
        assertTrue(data.tokenCleared)
    }

    @Test
    fun `propagates cancellation during storage access`() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        val data = FakeBootstrapData()
        data.serverReadFailure = cancellation

        val result = runCatching { BootstrapCoordinator(data, data).resolve() }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(0, data.validationCount)
        assertFalse(data.tokenCleared)
    }

    @Test
    fun `propagates unexpected storage failures`() = runBlocking {
        val failure = IllegalStateException("Unexpected failure")
        val data = FakeBootstrapData()
        data.serverReadFailure = failure

        val result = runCatching { BootstrapCoordinator(data, data).resolve() }

        assertSame(failure, result.exceptionOrNull())
    }
}

private class FakeBootstrapData(
    private val servers: List<SavedServer> = emptyList(),
    private val activeServerId: String? = servers.firstOrNull()?.id,
    private val token: String? = null,
    private val validation: AppResult<Session> = AppResult.Failure(AppError.Offline),
    private val pendingValidation: PendingValidation? = null,
) : ServerStore, SessionRepository {
    var tokenCleared = false
    var validationCount = 0
    var serverReadFailure: Exception? = null
    var activeServerReadFailure: Exception? = null
    var tokenReadFailure: Exception? = null
    var tokenClearFailure: Exception? = null

    override suspend fun getServers(): List<SavedServer> {
        serverReadFailure?.let { throw it }
        return servers
    }

    override suspend fun save(server: SavedServer) = error("Not used")

    override suspend fun delete(serverId: String): List<SavedServer> = error("Not used")

    override suspend fun getActiveServerId(): String? {
        activeServerReadFailure?.let { throw it }
        return activeServerId
    }

    override suspend fun setActiveServerId(serverId: String) = error("Not used")

    override suspend fun login(
        server: SavedServer,
        username: String,
        password: String,
    ): AppResult<Session> = error("Not used")

    override suspend fun getToken(serverId: String): String? {
        tokenReadFailure?.let { throw it }
        return token
    }

    override suspend fun validate(server: SavedServer, token: String): AppResult<Session> {
        validationCount += 1
        return pendingValidation?.await() ?: validation
    }

    override suspend fun clearToken(serverId: String) {
        tokenClearFailure?.let { throw it }
        tokenCleared = true
    }
}

private class PendingValidation {
    private var continuation: CancellableContinuation<Session>? = null

    suspend fun await(): AppResult<Session> = networkCall(Json) {
        // Keep the real cancellable callback bridge and HTTP error mapping.
        suspendCancellableCoroutine { continuation = it }
    }

    fun failUnauthorized() {
        checkNotNull(continuation).resumeWithException(
            HttpException(Response.error<Unit>(401, "".toResponseBody())),
        )
        continuation = null
    }
}

private fun savedServer() = SavedServer(
    id = "server-id",
    name = "家庭服务器",
    origin = "http://192.168.1.2:8000",
)

private fun session(server: SavedServer) = Session(
    server = server,
    token = "saved-token",
    user = SessionUser(id = 1, username = "tv_user", role = "user"),
)
