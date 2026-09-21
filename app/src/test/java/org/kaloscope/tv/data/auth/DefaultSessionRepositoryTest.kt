package org.kaloscope.tv.data.auth

import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.network.ApiClientFactory
import org.kaloscope.tv.core.storage.SessionStore
import org.kaloscope.tv.feature.login.LoginCoordinator
import org.kaloscope.tv.feature.login.LoginError

class DefaultSessionRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var savedServer: SavedServer
    private lateinit var store: FailingSessionStore
    private lateinit var repository: DefaultSessionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        savedServer = SavedServer(
            id = "server-id",
            name = "Test server",
            origin = server.url("/").toString().removeSuffix("/"),
        )
        store = FailingSessionStore()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        repository = DefaultSessionRepository(ApiClientFactory(json), store, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful login saves the selected server token before returning a session`() = runTest {
        store.tokens["other-server"] = "other-fixture-token"
        server.enqueue(successfulLoginResponse())

        val result = repository.login(savedServer, "tv_user", "fixture-password")

        assertEquals(
            AppResult.Success(
                Session(
                    server = savedServer,
                    token = "fixture-token-not-for-production",
                    user = SessionUser(1, "tv_user", "user"),
                ),
            ),
            result,
        )
        assertEquals(
            mapOf(
                "other-server" to "other-fixture-token",
                savedServer.id to "fixture-token-not-for-production",
            ),
            store.tokens,
        )
        assertEquals(1, store.writeAttempts)
    }

    @Test
    fun `token write failure clears login busy state and allows retry after recovery`() = runTest {
        store.tokens["other-server"] = "other-fixture-token"
        store.writeFailure = IOException("storage unavailable")
        server.enqueue(successfulLoginResponse())
        server.enqueue(successfulLoginResponse())
        val coordinator = LoginCoordinator(savedServer, repository)
        coordinator.updateUsername("tv_user")
        coordinator.updatePassword("fixture-password")

        val failedSession = coordinator.submit()

        assertNull(failedSession)
        assertEquals(LoginError.Request(AppError.SessionSaveFailed), coordinator.state.value.error)
        assertEquals("tv_user", coordinator.state.value.username)
        assertEquals("", coordinator.state.value.password)
        assertFalse(coordinator.state.value.isSubmitting)
        assertEquals(mapOf("other-server" to "other-fixture-token"), store.tokens)

        store.writeFailure = null
        coordinator.updatePassword("fixture-password")
        val recoveredSession = coordinator.submit()

        assertEquals(savedServer, recoveredSession?.server)
        assertEquals(recoveredSession?.token, store.tokens[savedServer.id])
        assertEquals("", coordinator.state.value.password)
        assertFalse(coordinator.state.value.isSubmitting)
        assertNull(coordinator.state.value.error)
        assertEquals(2, store.writeAttempts)
    }

    @Test
    fun `token encryption failure returns an error without changing saved tokens`() = runTest {
        store.tokens[savedServer.id] = "previous-fixture-token"
        store.writeFailure = GeneralSecurityException("key unavailable")
        server.enqueue(successfulLoginResponse())

        val result = repository.login(savedServer, "tv_user", "fixture-password")

        assertEquals(AppResult.Failure(AppError.SessionSaveFailed), result)
        assertEquals(mapOf(savedServer.id to "previous-fixture-token"), store.tokens)
        assertEquals(1, store.writeAttempts)
    }

    @Test
    fun `cancellation while saving the token propagates unchanged`() = runTest {
        val cancellation = CancellationException("test cancelled")
        store.writeFailure = cancellation
        server.enqueue(successfulLoginResponse())

        try {
            repository.login(savedServer, "tv_user", "fixture-password")
            fail("Cancellation must not become a login error")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }

        assertTrue(store.tokens.isEmpty())
        assertEquals(1, store.writeAttempts)
    }

    @Test
    fun `rejected login preserves the authentication error without attempting a token write`() =
        runTest {
            store.writeFailure = IOException("storage unavailable")
            server.enqueue(MockResponse().setResponseCode(401))

            val result = repository.login(savedServer, "tv_user", "fixture-password")

            assertEquals(AppResult.Failure(AppError.Unauthorized), result)
            assertEquals(0, store.writeAttempts)
            assertTrue(store.tokens.isEmpty())
        }

    private fun successfulLoginResponse() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            checkNotNull(javaClass.getResource("/fixtures/api/auth-login-success.json"))
                .readText(),
        )
}

private class FailingSessionStore : SessionStore {
    val tokens = mutableMapOf<String, String>()
    var writeAttempts = 0
    var writeFailure: Exception? = null

    override suspend fun getToken(serverId: String): String? = tokens[serverId]

    override suspend fun setToken(serverId: String, token: String) {
        writeAttempts += 1
        writeFailure?.let { throw it }
        tokens[serverId] = token
    }

    override suspend fun clearToken(serverId: String) = error("Not used")
}
