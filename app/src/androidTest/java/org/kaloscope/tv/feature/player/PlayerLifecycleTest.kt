package org.kaloscope.tv.feature.player

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.kaloscope.tv.R
import org.kaloscope.tv.app.KaloscopeTheme
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkVideoType
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.model.SubtitleTrack
import org.kaloscope.tv.core.player.PlaybackControllerFactory
import org.kaloscope.tv.core.player.PlaybackRequest
import org.kaloscope.tv.core.player.ProgressReason

class PlayerLifecycleTest {
    @get:Rule
    // Media3 listeners require main-thread cleanup.
    @Suppress("DEPRECATION")
    val composeRule = createComposeRule()

    private val progress = CopyOnWriteArrayList<Progress>()
    private lateinit var request: MutableState<PlaybackRequest.NetworkVideo>
    private lateinit var subtitles: MutableState<List<SubtitleTrack>>

    @Test
    fun pausedPlaybackRestoresLatestPositionAndStaysPaused() = withPlayer { owner ->
        pressProgressKey(Key.Enter)
        pressProgressKey(Key.DirectionRight)
        composeRule.waitUntil(10_000) {
            progress.any { it.reason == ProgressReason.Seeked && it.positionMillis >= 10_000 }
        }

        val stoppedPosition = stop(owner)
        val startCount = progress.count { it.reason == ProgressReason.Started }
        composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitStartAfter(startCount)

        assertPositionNear(stoppedPosition, progress.last { it.reason == ProgressReason.Started })
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.play),
        )
        assertPositionNear(stoppedPosition, Progress(stop(owner), ProgressReason.Exit))
    }

    @Test
    fun playingPlaybackRestoresLatestPositionAndKeepsPlaying() = withPlayer { owner ->
        pressProgressKey(Key.DirectionRight)
        composeRule.waitUntil(10_000) {
            progress.any { it.reason == ProgressReason.Seeked && it.positionMillis >= 10_000 }
        }

        val stoppedPosition = stop(owner)
        val startCount = progress.count { it.reason == ProgressReason.Started }
        composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitStartAfter(startCount)

        assertPositionNear(stoppedPosition, progress.last { it.reason == ProgressReason.Started })
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.pause),
        )
    }

    @Test
    fun changingEpisodeWhileStoppedDiscardsPreviousResumeState() = withPlayer { owner ->
        pressProgressKey(Key.Enter)
        stop(owner)
        val startCount = progress.count { it.reason == ProgressReason.Started }
        composeRule.runOnIdle {
            request.value = request.value.copy(
                source = request.value.source.copy(resourceId = "next-episode"),
                resumePositionMillis = 2_000,
            )
        }
        composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitStartAfter(startCount)

        assertPositionNear(2_000, progress.last { it.reason == ProgressReason.Started })
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.pause),
        )
    }

    @Test
    fun retriedSubtitlesLoadWithoutResumingPausedPlayback() = withPlayer { owner ->
        pressProgressKey(Key.Enter)
        pressProgressKey(Key.DirectionRight)
        composeRule.waitUntil(10_000) {
            progress.any { it.reason == ProgressReason.Seeked && it.positionMillis >= 10_000 }
        }
        val pausedPosition = progress.last { it.reason == ProgressReason.Seeked }.positionMillis

        loadRetriedSubtitles()

        assertPositionNear(pausedPosition, progress.last { it.reason == ProgressReason.Started })
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.play),
        )
        assertPositionNear(pausedPosition, Progress(stop(owner), ProgressReason.Exit))
    }

    @Test
    fun retriedSubtitlesLoadWhilePlaybackContinues() = withPlayer { _ ->
        pressProgressKey(Key.DirectionRight)
        composeRule.waitUntil(10_000) {
            progress.any { it.reason == ProgressReason.Seeked && it.positionMillis >= 10_000 }
        }
        val positionBeforeRetry = progress.last { it.reason == ProgressReason.Seeked }.positionMillis

        loadRetriedSubtitles()

        assertTrue(
            progress.last { it.reason == ProgressReason.Started }.positionMillis >= positionBeforeRetry,
        )
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.pause),
        )
    }

    private fun loadRetriedSubtitles() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("WEBVTT\n\n00:00:00.000 --> 00:01:00.000\nRetried subtitle\n"),
            )
            server.start()
            val startCount = progress.count { it.reason == ProgressReason.Started }
            val exitCount = progress.count { it.reason == ProgressReason.Exit }
            composeRule.runOnIdle {
                subtitles.value = listOf(
                    SubtitleTrack(
                        id = "retried-subtitle",
                        label = "English",
                        url = server.url("/subtitle.vtt").toString(),
                        language = "en",
                    ),
                )
            }
            composeRule.waitUntil(10_000) { server.requestCount > 0 }
            awaitStartAfter(startCount)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("player-subtitle-overlay")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(exitCount, progress.count { it.reason == ProgressReason.Exit })
        }
    }

    private fun pressProgressKey(key: Key) {
        composeRule.onNodeWithTag("player-progress")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(key) }
    }

    private fun stop(owner: PlayerLifecycleOwner): Long {
        val exitCount = progress.count { it.reason == ProgressReason.Exit }
        composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil(10_000) {
            progress.count { it.reason == ProgressReason.Exit } > exitCount
        }
        return progress.last { it.reason == ProgressReason.Exit }.positionMillis
    }

    private fun awaitStartAfter(count: Int) {
        composeRule.waitUntil(10_000) {
            progress.count { it.reason == ProgressReason.Started } > count
        }
    }

    private fun assertPositionNear(expected: Long, actual: Progress) {
        assertTrue(
            "Expected resume near $expected ms, got ${actual.positionMillis} ms",
            abs(actual.positionMillis - expected) < 500,
        )
    }

    private fun withPlayer(block: (PlayerLifecycleOwner) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("player-lifecycle-", ".wav", context.cacheDir)
        val visible = mutableStateOf(true)
        lateinit var owner: PlayerLifecycleOwner
        try {
            audio.writeBytes(silentAudio())
            subtitles = mutableStateOf(emptyList())
            request = mutableStateOf(
                PlaybackRequest.NetworkVideo(
                    requestId = "lifecycle-request",
                    serverId = "fixture-server",
                    title = "Lifecycle fixture",
                    source = NetworkPlaybackSource(
                        indexerId = 1,
                        resourceId = "fixture-resource",
                        title = "Lifecycle fixture",
                        url = Uri.fromFile(audio).toString(),
                        videoType = NetworkVideoType.Unknown,
                        danmakus = emptyList(),
                    ),
                    resumePositionMillis = 5_000,
                ),
            )
            composeRule.runOnUiThread {
                owner = PlayerLifecycleOwner().apply {
                    lifecycle.currentState = Lifecycle.State.RESUMED
                }
            }
            composeRule.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) {
                        KaloscopeTheme {
                            val playerContext = LocalContext.current
                            PlayerScreen(
                                session = Session(
                                    server = SavedServer("fixture-server", "Test", "https://server.example"),
                                    token = "fixture-token",
                                    user = SessionUser(1, "fixture-user", "user"),
                                ),
                                state = PlayerUiState.Content(
                                    request = request.value,
                                    subtitles = subtitles.value,
                                    danmakus = emptyList(),
                                    extraFailures = emptyMap(),
                                ),
                                controllerFactory = remember(playerContext) {
                                    PlaybackControllerFactory(playerContext)
                                },
                                onProgress = { _, position, _, reason ->
                                    progress += Progress(position, reason)
                                },
                                onSelectDefinition = { _, _ -> },
                                onPrevious = {},
                                onNext = {},
                                onSelectEpisode = {},
                                onRetryExtra = {},
                                onBack = {},
                            )
                        }
                    }
                }
            }
            awaitStartAfter(0)
            block(owner)
        } finally {
            composeRule.runOnIdle { visible.value = false }
            composeRule.waitForIdle()
            audio.delete()
        }
    }

    private data class Progress(val positionMillis: Long, val reason: ProgressReason)
}

private class PlayerLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this)
}

private fun silentAudio(): ByteArray {
    // A local PCM stream exercises real Media3 seek/lifecycle behavior without a server or codecs.
    val sampleRate = 8_000
    val byteCount = sampleRate * 2 * 60
    return ByteBuffer.allocate(44 + byteCount).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt(36 + byteCount)
        .put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1)
        .putShort(1)
        .putInt(sampleRate)
        .putInt(sampleRate * 2)
        .putShort(2)
        .putShort(16)
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(byteCount)
        .array()
}
