package org.kaloscope.tv.data.media

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.network.ApiClientFactory

class DefaultMediaRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultMediaRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        repository = DefaultMediaRepository(ApiClientFactory(json), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `malformed media page rating returns invalid data and allows retry`() = runTest {
        for (rating in listOf("[]", "{}")) {
            server.enqueue(
                response(
                    """
                    {"total":1,"items":[{
                      "id":201,"name":"Video","path":"/media/video.mkv","rating":$rating
                    }]}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                response(
                    """
                    {"total":1,"items":[{
                      "id":201,"name":"Video","path":"/media/video.mkv","rating":"8.6"
                    }]}
                    """.trimIndent(),
                ),
            )
            val requestsBefore = server.requestCount

            val failed = repository.getMediaPage(session(), 21, 1, 20, null)

            assertTrue((failed as AppResult.Failure).error is AppError.InvalidData)
            assertEquals(requestsBefore + 1, server.requestCount)

            val retried = repository.getMediaPage(session(), 21, 1, 20, null)

            val item = (retried as AppResult.Success).value.items.single()
            assertEquals(201L, item.id)
            assertEquals(8.6, item.rating)
            assertEquals(requestsBefore + 2, server.requestCount)
        }
    }

    @Test
    fun `malformed media detail rating returns invalid data and allows retry`() = runTest {
        for (rating in listOf("{}", "[]")) {
            server.enqueue(
                response(
                    """
                    {"id":201,"name":"Video","path":"/media/video.mkv","rating":$rating}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                response(
                    """
                    {"id":201,"name":"Video","path":"/media/video.mkv","rating":8.6}
                    """.trimIndent(),
                ),
            )
            val requestsBefore = server.requestCount

            val failed = repository.getMediaDetail(session(), 201)

            assertTrue((failed as AppResult.Failure).error is AppError.InvalidData)
            assertEquals(requestsBefore + 1, server.requestCount)

            val retried = repository.getMediaDetail(session(), 201)

            val item = (retried as AppResult.Success).value
            assertEquals(201L, item.id)
            assertEquals(8.6, item.rating)
            assertEquals(requestsBefore + 2, server.requestCount)
        }
    }

    private fun session() = Session(
        server = SavedServer(
            id = "server-id",
            name = "Home",
            origin = server.url("/").toString().removeSuffix("/"),
        ),
        token = "fixture-token",
        user = SessionUser(1, "tv", "user"),
    )

    private fun response(data: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"status":200,"message":"","data":$data}""")
}
