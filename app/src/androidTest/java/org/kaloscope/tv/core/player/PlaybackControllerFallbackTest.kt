package org.kaloscope.tv.core.player

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser

class PlaybackControllerFallbackTest {
    @Test
    fun autoFallbackPreservesPausedResumeState() {
        assertFallbackPreservesPlayWhenReady(false)
    }

    @Test
    fun autoFallbackPreservesPlayingResumeState() {
        assertFallbackPreservesPlayWhenReady(true)
    }

    private fun assertFallbackPreservesPlayWhenReady(playWhenReady: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.requestUrl?.queryParameter("transcode") == "true") {
                        // Keep HLS loading without another source error or a playable segment.
                        MockResponse().setBody(
                            "#EXTM3U\n#EXT-X-VERSION:3\n" +
                                "#EXT-X-TARGETDURATION:600\n#EXT-X-MEDIA-SEQUENCE:0\n",
                        )
                    } else {
                        // A successful HTTP response with unsupported media triggers Auto fallback.
                        MockResponse().setBody("Unsupported media container")
                    }
            }
            server.start()
            lateinit var controller: PlaybackController
            instrumentation.runOnMainSync {
                controller = PlaybackControllerFactory(instrumentation.targetContext).create(
                    session = Session(
                        server = SavedServer(
                            "fixture-server",
                            "Test",
                            server.url("/").toString().removeSuffix("/"),
                        ),
                        token = "fixture-token",
                        user = SessionUser(1, "fixture-user", "user"),
                    ),
                    request = PlaybackRequest.LocalMedia(
                        requestId = "fallback-request",
                        serverId = "fixture-server",
                        mediaId = 1,
                        path = "/fixture.mkv",
                        title = "Fallback fixture",
                        resumePositionSeconds = 0,
                        origin = PlaybackOrigin.MediaDetail,
                        playbackMode = PlaybackMode.Auto,
                    ),
                    subtitles = emptyList(),
                    resumeState = PlaybackResumeState(0, playWhenReady),
                    onProgress = { _, _, _, _ -> },
                )
            }
            try {
                runBlocking {
                    withTimeout(15_000) {
                        controller.status.first {
                            it.sourceKind == PlaybackSourceKind.HlsTranscode
                        }
                    }
                }
                val directRequest = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                val fallbackRequest = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                assertNull(directRequest.requestUrl?.queryParameter("transcode"))
                assertEquals("true", fallbackRequest.requestUrl?.queryParameter("transcode"))
                // The fallback status is published before the replacement source is prepared.
                instrumentation.runOnMainSync {
                    assertEquals(playWhenReady, controller.player.playWhenReady)
                    assertNull(controller.status.value.failure)
                }
            } finally {
                instrumentation.runOnMainSync { controller.release() }
            }
        }
    }
}
