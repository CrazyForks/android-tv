package org.kaloscope.tv.core.designsystem

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.kaloscope.tv.app.KaloscopeTheme

class KaloscopeChoiceDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dialogUsesCenteredFixedWidthPanelAndFocusesFirstSelectedOption() {
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints {
                    KaloscopeChoiceDialog(
                        title = "屏蔽类型",
                        viewportSize = DpSize(maxWidth, maxHeight),
                        options = listOf(
                            option("滚动", selected = false, tag = "choice-scroll"),
                            option("顶部", selected = true, tag = "choice-top"),
                            option("底部", selected = false, tag = "choice-bottom"),
                        ),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("choice-top").assertIsFocused()
        val overlayBounds = composeRule.onNodeWithTag("kaloscope-choice-dialog-overlay")
            .getUnclippedBoundsInRoot()
        val panelBounds = composeRule.onNodeWithTag("kaloscope-choice-dialog-panel")
            .getUnclippedBoundsInRoot()

        assertEquals(420.dp, panelBounds.right - panelBounds.left)
        assertEquals(
            (overlayBounds.left + overlayBounds.right) / 2,
            (panelBounds.left + panelBounds.right) / 2,
        )
        assertEquals(
            (overlayBounds.top + overlayBounds.bottom) / 2,
            (panelBounds.top + panelBounds.bottom) / 2,
        )
    }

    @Test
    fun entranceKeepsLayoutSizeAndDoesNotRestartOnRecomposition() {
        composeRule.mainClock.autoAdvance = false
        var title by mutableStateOf("选项")
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints {
                    KaloscopeChoiceDialog(
                        title = title,
                        viewportSize = DpSize(maxWidth, maxHeight),
                        options = listOf(
                            option("自动", selected = true, tag = "animated-choice-auto"),
                            option("直连", selected = false, tag = "animated-choice-direct"),
                        ),
                        onDismiss = {},
                    )
                }
            }
        }

        val options = composeRule.onNodeWithTag("kaloscope-choice-dialog-options")
        val initialNode = options.fetchSemanticsNode()
        val initialBounds = initialNode.boundsInRoot
        val layoutWidth = initialNode.layoutInfo.width
        val layoutHeight = initialNode.layoutInfo.height

        composeRule.mainClock.advanceTimeBy(80)
        val enteringWidth = options.fetchSemanticsNode().boundsInRoot.width
        composeRule.mainClock.advanceTimeBy(240)
        val settledNode = options.fetchSemanticsNode()
        val settledBounds = settledNode.boundsInRoot

        assertEquals(settledBounds.width * 0.98f, initialBounds.width, 1f)
        assertTrue(enteringWidth > initialBounds.width)
        assertTrue(enteringWidth < settledBounds.width)
        assertEquals(layoutWidth, settledNode.layoutInfo.width)
        assertEquals(layoutHeight, settledNode.layoutInfo.height)
        assertEquals(initialBounds.center.x, settledBounds.center.x, 1f)

        composeRule.runOnIdle { title = "播放方式" }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(settledBounds, options.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag("animated-choice-auto").assertIsFocused()
    }

    @Test
    fun backDuringEntranceDismissesImmediatelyAndAllowsReopening() {
        composeRule.mainClock.autoAdvance = false
        var open by mutableStateOf(true)
        var dismissCount = 0
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints {
                    if (open) {
                        KaloscopeChoiceDialog(
                            title = "播放方式",
                            viewportSize = DpSize(maxWidth, maxHeight),
                            options = listOf(
                                option("自动", selected = true, tag = "early-choice-auto"),
                                option("直连", selected = false, tag = "early-choice-direct"),
                            ),
                            onDismiss = {
                                dismissCount += 1
                                open = false
                            },
                        )
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(48)
        composeRule.onNodeWithTag("early-choice-auto")
            .assertIsFocused()
            .performKeyInput {
                pressKey(Key.DirectionUp)
                pressKey(Key.DirectionLeft)
                pressKey(Key.DirectionRight)
            }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("early-choice-direct").assertIsFocused()

        InstrumentationRegistry.getInstrumentation().apply {
            waitForIdleSync()
            sendKeyDownUpSync(AndroidKeyEvent.KEYCODE_BACK)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("kaloscope-choice-dialog-panel").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.onNodeWithTag("kaloscope-choice-dialog-panel").assertDoesNotExist()

        composeRule.runOnIdle { open = true }
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.onNodeWithTag("early-choice-auto").assertIsFocused()
        val enteringWidth = composeRule.onNodeWithTag("kaloscope-choice-dialog-options")
            .fetchSemanticsNode().boundsInRoot.width
        composeRule.mainClock.advanceTimeBy(240)
        val settledWidth = composeRule.onNodeWithTag("kaloscope-choice-dialog-options")
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue(enteringWidth < settledWidth)
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun overflowingOptionsKeepDpadFocusVisibleInsideTheViewport() {
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints(
                    modifier = Modifier
                        .width(872.dp)
                        .height(416.dp),
                ) {
                    KaloscopeChoiceDialog(
                        title = "阅读主题",
                        viewportSize = DpSize(maxWidth, maxHeight),
                        options = List(8) { index ->
                            option(
                                label = "主题 $index",
                                selected = index == 0,
                                tag = "overflow-choice-$index",
                            )
                        },
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("overflow-choice-0")
            .assertIsFocused()
            .performKeyInput {
                repeat(7) { pressKey(Key.DirectionDown) }
            }

        composeRule.onNodeWithTag("overflow-choice-7")
            .assertIsFocused()
            .assertIsDisplayed()
        val overlayBounds = composeRule.onNodeWithTag("kaloscope-choice-dialog-overlay")
            .getUnclippedBoundsInRoot()
        val panelBounds = composeRule.onNodeWithTag("kaloscope-choice-dialog-panel")
            .getUnclippedBoundsInRoot()
        assertTrue(panelBounds.top >= overlayBounds.top)
        assertTrue(panelBounds.bottom <= overlayBounds.bottom)
    }

    @Test
    fun overflowingOptionsInitiallyShowTheSelectedFinalOption() {
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints(
                    modifier = Modifier
                        .width(872.dp)
                        .height(416.dp),
                ) {
                    KaloscopeChoiceDialog(
                        title = "阅读主题",
                        viewportSize = DpSize(maxWidth, maxHeight),
                        options = List(8) { index ->
                            option(
                                label = "主题 $index",
                                selected = index == 7,
                                tag = "selected-final-choice-$index",
                            )
                        },
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("selected-final-choice-7")
            .assertIsFocused()
            .assertIsDisplayed()
    }

    @Test
    fun multiSelectStaysOpenUntilBackDismissesIt() {
        var open by mutableStateOf(true)
        var selected by mutableStateOf(emptySet<String>())
        var dismissCount = 0
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints {
                    if (open) {
                        KaloscopeChoiceDialog(
                            title = "屏蔽类型",
                            viewportSize = DpSize(maxWidth, maxHeight),
                            dismissOnSelect = false,
                            selectionIndicator = KaloscopeSelectionIndicatorType.Checkbox,
                            options = listOf("滚动", "顶部").map { label ->
                                KaloscopeChoiceDialogOption(
                                    label = label,
                                    selected = { label in selected },
                                    testTag = "choice-$label",
                                    onSelect = {
                                        selected = if (label in selected) {
                                            selected - label
                                        } else {
                                            selected + label
                                        }
                                    },
                                )
                            },
                            onDismiss = {
                                dismissCount += 1
                                open = false
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(
            testTag = "choice-滚动-checkbox-indicator",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithTag(
            testTag = "choice-顶部-checkbox-indicator",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithTag(
            testTag = "choice-滚动-checkbox-indicator-mark",
            useUnmergedTree = true,
        ).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("choice-滚动")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("choice-滚动").assertIsSelected().assertIsFocused()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNodeWithTag(
            testTag = "choice-滚动-checkbox-indicator-mark",
            useUnmergedTree = true,
        ).assertExists()

        composeRule.onNodeWithTag("choice-滚动").performKeyInput { pressKey(Key.Enter) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("choice-滚动").assertIsNotSelected().assertIsFocused()
        composeRule.runOnIdle { assertTrue(selected.isEmpty()) }
        composeRule.mainClock.advanceTimeBy(16)
        composeRule.onNodeWithTag("choice-滚动").performKeyInput { pressKey(Key.Enter) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("choice-滚动").assertIsSelected().assertIsFocused()

        composeRule.onNodeWithTag("choice-顶部")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("choice-顶部").assertIsSelected().assertIsFocused()
        composeRule.onNodeWithTag(
            testTag = "choice-顶部-checkbox-indicator-mark",
            useUnmergedTree = true,
        ).assertExists()

        composeRule.onNodeWithTag("kaloscope-choice-dialog-panel").assertExists()
        composeRule.runOnIdle {
            assertEquals(setOf("滚动", "顶部"), selected)
            assertEquals(0, dismissCount)
        }

        InstrumentationRegistry.getInstrumentation().apply {
            waitForIdleSync()
            sendKeyDownUpSync(AndroidKeyEvent.KEYCODE_BACK)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("kaloscope-choice-dialog-panel").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun singleSelectDismissesAfterSelection() {
        var open by mutableStateOf(true)
        var selected = false
        composeRule.setContent {
            KaloscopeTheme {
                BoxWithConstraints {
                    if (open) {
                        KaloscopeChoiceDialog(
                            title = "选项",
                            viewportSize = DpSize(maxWidth, maxHeight),
                            options = listOf(
                                KaloscopeChoiceDialogOption(
                                    label = "选中",
                                    selected = { selected },
                                    testTag = "single-option",
                                    onSelect = { selected = true },
                                ),
                            ),
                            onDismiss = { open = false },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(
            testTag = "single-option-checkbox-indicator",
            useUnmergedTree = true,
        ).assertDoesNotExist()
        composeRule.onNodeWithTag(
            testTag = "single-option-radio-indicator",
            useUnmergedTree = true,
        ).assertDoesNotExist()
        composeRule.onNodeWithTag("single-option")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }

        composeRule.onNodeWithTag("kaloscope-choice-dialog-panel").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(open) }
    }

    private fun option(
        label: String,
        selected: Boolean,
        tag: String,
    ) = KaloscopeChoiceDialogOption(
        label = label,
        selected = { selected },
        testTag = tag,
        onSelect = {},
    )
}
