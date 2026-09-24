package org.kaloscope.tv.core.player

import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser

class PlaybackControllerProgressTest {
    @Test
    fun pausingWhileBufferingRecordsResumePositionOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pausedPositions = CopyOnWriteArrayList<Long>()
        MockWebServer().use { server ->
            // Hold preparation so pausing cannot change isPlaying, which is already false.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
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
                        requestId = "buffering-progress",
                        serverId = "fixture-server",
                        mediaId = 1,
                        path = "/fixture.mp4",
                        title = "Buffering progress fixture",
                        resumePositionSeconds = 12,
                        origin = PlaybackOrigin.MediaDetail,
                        playbackMode = PlaybackMode.Direct,
                    ),
                    subtitles = emptyList(),
                    onProgress = { _, position, _, reason ->
                        if (reason == ProgressReason.Paused) pausedPositions += position
                    },
                )
            }
            try {
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                runBlocking {
                    withTimeout(10_000) {
                        controller.status.first {
                            it.playbackState == Player.STATE_BUFFERING && it.playWhenReady
                        }
                    }
                }
                instrumentation.runOnMainSync {
                    assertFalse(controller.player.isPlaying)
                    assertTrue(pausedPositions.isEmpty())
                    assertFalse(controller.togglePlayPause())
                }
                runBlocking {
                    withTimeout(10_000) {
                        controller.status.first {
                            it.playbackState == Player.STATE_BUFFERING && !it.playWhenReady
                        }
                    }
                }
                assertEquals(listOf(12_000L), pausedPositions)

                instrumentation.runOnMainSync { controller.player.pause() }
                instrumentation.waitForIdleSync()
                assertEquals(listOf(12_000L), pausedPositions)
            } finally {
                instrumentation.runOnMainSync { controller.release() }
            }
        }
    }
}
