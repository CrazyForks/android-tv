package org.kaloscope.tv.feature.player

import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import org.kaloscope.tv.core.model.NetworkDefinition
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkVideoType
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.model.SubtitleTrack
import org.kaloscope.tv.core.player.PlaybackControllerFactory
import org.kaloscope.tv.core.player.PlaybackRequest
import org.kaloscope.tv.core.player.PlaybackRequestNavigator
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
    fun endedPlaybackRestartsWithCenterOnProgress() {
        assertEndedPlaybackRestarts("player-progress")
    }

    @Test
    fun endedPlaybackRestartsWithCenterOnPlayButton() {
        assertEndedPlaybackRestarts("player-play-pause")
    }

    @Test
    fun failureWithHiddenControlsKeepsRetryFocusAndAcceptsCenter() {
        assertFailureAllowsRemoteRetry(showPreview = false, confirmKey = Key.DirectionCenter)
    }

    @Test
    fun failureWithPreviewKeepsRetryFocusAndAcceptsEnter() {
        assertFailureAllowsRemoteRetry(showPreview = true, confirmKey = Key.Enter)
    }

    private fun assertFailureAllowsRemoteRetry(showPreview: Boolean, confirmKey: Key) =
        withPlayer { _ ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            pressProgressKey(Key.Enter)
            instrumentation.sendKeyDownUpSync(AndroidKeyEvent.KEYCODE_BACK)
            composeRule.onNodeWithTag("player-progress").assertDoesNotExist()
            if (showPreview) {
                composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
                composeRule.onNodeWithTag("player-info-preview").assertIsDisplayed()
            }

            val audio = File(checkNotNull(Uri.parse(request.value.source.url).path))
            val audioBytes = audio.readBytes()
            val subtitle = File.createTempFile("player-retry-", ".vtt", context.cacheDir)
            try {
                subtitle.writeText("WEBVTT\n\n00:00:00.000 --> 00:01:00.000\nRetry fixture\n")
                // Reload the same source after removing the fixture so failure occurs after
                // hiding controls, without replacing the playback session or its control layer.
                assertTrue(audio.delete())
                composeRule.runOnIdle {
                    subtitles.value = listOf(
                        SubtitleTrack(
                            id = "retry-fixture",
                            label = "English",
                            url = Uri.fromFile(subtitle).toString(),
                            language = "en",
                        ),
                    )
                }
                composeRule.waitUntil(10_000) { progress.any { it.reason == ProgressReason.Error } }
                val retry = composeRule.onNodeWithText(context.getString(R.string.retry))
                retry.assertIsDisplayed().assertIsFocused()

                instrumentation.sendKeyDownUpSync(AndroidKeyEvent.KEYCODE_BACK)
                composeRule.onNodeWithTag("player-exit-confirmation").assertIsDisplayed()
                for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
                    retry.performKeyInput { pressKey(key) }.assertIsFocused()
                }
                composeRule.onNodeWithTag("player-exit-confirmation").assertDoesNotExist()

                audio.writeBytes(audioBytes)
                val startsBefore = progress.count { it.reason == ProgressReason.Started }
                retry.performKeyInput { pressKey(confirmKey) }
                awaitStartAfter(startsBefore)

                retry.assertDoesNotExist()
                // Retry can briefly lose duration and focus Play/Pause until it is known again.
                composeRule.onAllNodes(
                    (hasTestTag("player-progress") or hasTestTag("player-play-pause")) and isFocused(),
                ).assertCountEquals(1)
            } finally {
                subtitle.delete()
            }
        }

    private fun assertEndedPlaybackRestarts(controlTag: String) =
        withPlayer(resumePositionMillis = 59_000) { owner ->
            pressProgressKey(Key.DirectionDown)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(
                    hasTestTag("player-play-pause") and
                        hasContentDescription(context.getString(R.string.play)),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            val startCount = progress.count { it.reason == ProgressReason.Started }

            composeRule.onNodeWithTag(controlTag)
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }

            awaitStartAfter(startCount)
            assertPositionNear(0, progress.last { it.reason == ProgressReason.Started })
            pressProgressKey(Key.DirectionDown)
            composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
                context.getString(R.string.pause),
            )
            assertTrue(stop(owner) < 10_000)
        }

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
    fun changingQualityWhilePausedRetainsPositionAndStaysPaused() = withPlayer { owner ->
        pressProgressKey(Key.Enter)
        pressProgressKey(Key.DirectionRight)
        composeRule.waitUntil(10_000) {
            progress.any { it.reason == ProgressReason.Seeked && it.positionMillis >= 10_000 }
        }
        val pausedPosition = progress.last { it.reason == ProgressReason.Seeked }.positionMillis

        selectAlternateDefinition()

        assertPositionNear(pausedPosition, progress.last { it.reason == ProgressReason.Started })
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.play),
        )
        assertPositionNear(pausedPosition, Progress(stop(owner), ProgressReason.Exit))
    }

    @Test
    fun changingQualityWhilePlayingRetainsPositionAndKeepsPlaying() = withPlayer { _ ->
        pressProgressKey(Key.DirectionRight)
        composeRule.waitUntil(10_000) {
            progress.any { it.reason == ProgressReason.Seeked && it.positionMillis >= 10_000 }
        }

        selectAlternateDefinition()

        assertPositionNear(
            progress.last { it.reason == ProgressReason.Exit }.positionMillis,
            progress.last { it.reason == ProgressReason.Started },
        )
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-play-pause").assertContentDescriptionEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.pause),
        )
    }

    @Test
    fun changingQualityWhileStoppedRetainsPausedResumeState() = withPlayer { owner ->
        pressProgressKey(Key.Enter)
        val stoppedPosition = stop(owner)
        val startCount = progress.count { it.reason == ProgressReason.Started }
        composeRule.runOnIdle {
            request.value = requireNotNull(
                PlaybackRequestNavigator.selectDefinition(request.value, 1, stoppedPosition),
            )
        }
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

    private fun selectAlternateDefinition() {
        val startCount = progress.count { it.reason == ProgressReason.Started }
        val exitCount = progress.count { it.reason == ProgressReason.Exit }
        pressProgressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("player-quality")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithText("720p")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        awaitStartAfter(startCount)
        composeRule.runOnIdle {
            assertEquals(1, request.value.source.selectedDefinitionIndex)
            assertEquals(exitCount + 1, progress.count { it.reason == ProgressReason.Exit })
        }
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

    private fun withPlayer(
        resumePositionMillis: Long = 5_000,
        block: (PlayerLifecycleOwner) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("player-lifecycle-", ".wav", context.cacheDir)
        val alternateAudio = File.createTempFile("player-quality-", ".wav", context.cacheDir)
        val visible = mutableStateOf(true)
        lateinit var owner: PlayerLifecycleOwner
        try {
            val audioBytes = silentAudio()
            audio.writeBytes(audioBytes)
            alternateAudio.writeBytes(audioBytes)
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
                        definitions = listOf(
                            NetworkDefinition("1080p", Uri.fromFile(audio).toString()),
                            NetworkDefinition("720p", Uri.fromFile(alternateAudio).toString()),
                        ),
                        selectedDefinitionIndex = 0,
                    ),
                    resumePositionMillis = resumePositionMillis,
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
                                onSelectDefinition = { index, position ->
                                    PlaybackRequestNavigator.selectDefinition(
                                        request.value,
                                        index,
                                        position,
                                    )?.let { request.value = it }
                                },
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
            alternateAudio.delete()
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
