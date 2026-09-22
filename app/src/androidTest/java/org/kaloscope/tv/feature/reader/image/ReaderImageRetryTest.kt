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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.kaloscope.tv.app.KaloscopeTheme
import org.kaloscope.tv.core.model.ImageReadMode
import org.kaloscope.tv.core.model.ImageReaderSettings
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
            images = listOf(server.url(path).toString()),
            imageCount = 1,
        )
        composeRule.setContent {
            KaloscopeTheme {
                ImageReaderSurface(
                    session = session,
                    content = content,
                    settings = ImageReaderSettings(readMode = readMode),
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

    private fun imageResponse(): MockResponse {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
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
