package org.kaloscope.tv.feature.reader.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.kaloscope.tv.app.KaloscopeTheme
import org.kaloscope.tv.core.model.ImageReadMode
import org.kaloscope.tv.core.model.ImageReaderSettings
import org.kaloscope.tv.core.model.ImageZoomMode
import org.kaloscope.tv.core.model.ReaderImageContent
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser

class ReaderImageRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val manualRetryRevision = mutableIntStateOf(0)
    private val failedImagesAvailable = mutableStateOf(false)
    private val contentRevision = mutableLongStateOf(0L)
    private val controlsVisible = mutableStateOf(false)
    private val readerVisible = mutableStateOf(true)

    @Test
    fun automaticRetryReloadsTheSameUrlAfterFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(imageResponse())
            server.start()
            setReader(server, "/automatic.png")

            awaitImageLoaded(server, expectedRequests = 2)
            assertRequests(server, "/automatic.png", count = 2)
        }
    }

    @Test
    fun finalAutomaticRetryDoesNotShowFailureBeforeItCompletes() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
            server.enqueue(imageResponse())
            server.start()
            composeRule.mainClock.autoAdvance = false
            try {
                setReader(server, "/last-automatic.png")

                // Observe every frame so the wait before the last retry cannot be skipped.
                composeRule.waitUntil(10_000) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.onNodeWithTag("reader-image-failed").assertDoesNotExist()
                    server.requestCount >= 4 &&
                        composeRule.onAllNodesWithTag("reader-image-current-loading")
                            .fetchSemanticsNodes().isEmpty()
                }

                awaitImageLoaded(server, expectedRequests = 4)
                assertRequests(server, "/last-automatic.png", count = 4)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun manualRetryRestartsLoadingAndTheAutomaticRetryBudget() {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
            server.start()
            setReader(server, "/manual.png")

            composeRule.waitUntil(10_000) { failedImagesAvailable.value }
            composeRule.onNodeWithTag("reader-image-failed").assertIsDisplayed()
            composeRule.onNodeWithTag("reader-image-current-loading").assertDoesNotExist()
            assertEquals(4, server.requestCount)
            assertRequests(server, "/manual.png", count = 4)
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))

            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(imageResponse())
            composeRule.runOnIdle { manualRetryRevision.intValue += 1 }

            awaitImageLoaded(server, expectedRequests = 6)
            assertRequests(server, "/manual.png", count = 2)
        }
    }

    @Test
    fun manualRetryDoesNotReloadAnImageRecoveredByTheLastAutomaticRetry() {
        MockWebServer().use { server ->
            val recoveredRequests = AtomicInteger()
            val failedRequests = AtomicInteger()
            server.start()
            val recoveredUrl = server.url("/recovered.png").toString()
            val failedUrl = server.url("/failed.png").toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.requestUrl?.queryParameter("url")) {
                        recoveredUrl -> if (recoveredRequests.incrementAndGet() <= 3) {
                            MockResponse().setResponseCode(500)
                        } else {
                            imageResponse(width = 256)
                        }
                        failedUrl -> if (failedRequests.incrementAndGet() <= 4) {
                            MockResponse().setResponseCode(500)
                        } else {
                            imageResponse(width = 256)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }
            withUncachedImageLoader {
                setReader(
                    server = server,
                    paths = listOf("/recovered.png", "/failed.png"),
                    settings = ImageReaderSettings(
                        readMode = ImageReadMode.Scroll,
                        zoomMode = ImageZoomMode.FitWidth,
                    ),
                )
                composeRule.waitUntil(10_000) {
                    recoveredRequests.get() >= 4 && failedRequests.get() >= 4 &&
                        failedImagesAvailable.value &&
                        composeRule.onAllNodesWithTag("reader-image-0-loading")
                            .fetchSemanticsNodes().isEmpty() &&
                        composeRule.onAllNodesWithTag("reader-image-1-loading")
                            .fetchSemanticsNodes().isEmpty()
                }
                // Both images stay composed, so preloading cannot account for extra requests.
                composeRule.onNodeWithTag("reader-image-0").assertIsDisplayed()
                composeRule.onNodeWithTag("reader-image-1").assertIsDisplayed()
                composeRule.onNodeWithTag("reader-image-failed").assertIsDisplayed()
                assertEquals(4, recoveredRequests.get())
                assertEquals(4, failedRequests.get())
                assertEquals(8, server.requestCount)
                repeat(8) { checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }

                composeRule.runOnIdle { manualRetryRevision.intValue += 1 }

                composeRule.waitUntil(10_000) {
                    failedRequests.get() >= 5 && !failedImagesAvailable.value &&
                        composeRule.onAllNodesWithTag("reader-image-1-loading")
                            .fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNodeWithTag("reader-image-failed").assertDoesNotExist()
                composeRule.onNodeWithTag("reader-image-0-loading").assertDoesNotExist()
                assertRequests(server, "/failed.png", count = 1)
                assertNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(4, recoveredRequests.get())
                assertEquals(5, failedRequests.get())
            }
        }
    }

    @Test
    fun chapterChangeRestartsFailedImageInPagedMode() {
        assertChapterChangeRestartsFailedImage(ImageReadMode.Paged)
    }

    @Test
    fun chapterChangeRestartsFailedImageInScrollMode() {
        assertChapterChangeRestartsFailedImage(ImageReadMode.Scroll)
    }

    private fun assertChapterChangeRestartsFailedImage(readMode: ImageReadMode) {
        val loadingTag = when (readMode) {
            ImageReadMode.Paged -> "reader-image-current-loading"
            ImageReadMode.Scroll -> "reader-image-0-loading"
        }
        MockWebServer().use { server ->
            repeat(8) { server.enqueue(MockResponse().setResponseCode(500)) }
            server.start()
            setReader(server, "/shared-chapter-image.png", readMode)

            composeRule.waitUntil(10_000) { failedImagesAvailable.value }
            composeRule.onNodeWithTag("reader-image-failed").assertIsDisplayed()
            assertEquals(4, server.requestCount)
            assertRequests(server, "/shared-chapter-image.png", count = 4)

            composeRule.runOnIdle { controlsVisible.value = true }
            composeRule.onNodeWithTag("reader-image-failed").assertIsDisplayed()
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))

            composeRule.runOnIdle {
                contentRevision.longValue += 1
                // ReaderScreen also clears its retry availability when the chapter changes.
                failedImagesAvailable.value = false
            }

            composeRule.waitUntil(10_000) {
                server.requestCount == 8 && failedImagesAvailable.value
            }
            composeRule.onNodeWithTag("reader-image-failed").assertIsDisplayed()
            composeRule.onNodeWithTag(loadingTag).assertDoesNotExist()
            assertRequests(server, "/shared-chapter-image.png", count = 4)

            server.enqueue(imageResponse())
            composeRule.runOnIdle { manualRetryRevision.intValue += 1 }

            awaitImageLoaded(server, expectedRequests = 9, loadingTag = loadingTag)
            assertRequests(server, "/shared-chapter-image.png", count = 1)
        }
    }

    private fun setReader(
        server: MockWebServer,
        path: String,
        readMode: ImageReadMode = ImageReadMode.Paged,
    ) = setReader(server, listOf(path), ImageReaderSettings(readMode = readMode))

    private fun setReader(
        server: MockWebServer,
        paths: List<String>,
        settings: ImageReaderSettings,
    ) {
        val session = Session(
            server = SavedServer("fixture-server", "Test", server.url("/").toString()),
            token = "fixture-token",
            user = SessionUser(1, "fixture-user", "user"),
        )
        val content = ReaderImageContent.network(
            indexerId = 1,
            resourceId = "fixture-resource",
            title = "Retry fixture",
            images = paths.map { server.url(it).toString() },
            imageCount = paths.size,
        )
        composeRule.setContent {
            KaloscopeTheme {
                if (!readerVisible.value) return@KaloscopeTheme
                ImageReaderSurface(
                    session = session,
                    content = content,
                    settings = settings,
                    contentRevision = contentRevision.longValue,
                    imagesExhausted = true,
                    isLoadingMore = false,
                    controlsVisible = controlsVisible.value,
                    focusRequester = remember { FocusRequester() },
                    onToggleControls = {},
                    onEnterControls = {},
                    onBoundary = {},
                    onLoadMore = {},
                    onPositionChanged = {},
                    manualRetryRevision = manualRetryRevision.intValue,
                    onFailedImagesChanged = { failedImagesAvailable.value = it },
                )
            }
        }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun withUncachedImageLoader(block: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousLoader = SingletonImageLoader.get(context)
        // An unintended reload must reach the server instead of succeeding from memory or disk.
        val imageLoader = ImageLoader.Builder(context)
            .memoryCache(null)
            .diskCache(null)
            .build()
        SingletonImageLoader.setUnsafe(imageLoader)
        try {
            block()
        } finally {
            try {
                composeRule.runOnIdle { readerVisible.value = false }
                composeRule.waitForIdle()
            } finally {
                SingletonImageLoader.setUnsafe(previousLoader)
                imageLoader.shutdown()
            }
        }
    }

    private fun awaitImageLoaded(
        server: MockWebServer,
        expectedRequests: Int,
        loadingTag: String = "reader-image-current-loading",
    ) {
        composeRule.waitUntil(10_000) {
            server.requestCount >= expectedRequests &&
                composeRule.onAllNodesWithTag(loadingTag)
                    .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("Retry fixture").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-image-failed").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(failedImagesAvailable.value) }
        assertEquals(expectedRequests, server.requestCount)
    }

    private fun assertRequests(server: MockWebServer, path: String, count: Int) {
        repeat(count) {
            val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/_api/image/proxy", request.requestUrl?.encodedPath)
            assertEquals(server.url(path).toString(), request.requestUrl?.queryParameter("url"))
            assertEquals("Token fixture-token", request.getHeader("Authorization"))
        }
    }

    private fun imageResponse(width: Int = 16): MockResponse {
        val bitmap = Bitmap.createBitmap(width, 16, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.GREEN)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        return MockResponse()
            .setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(bytes))
    }
}
