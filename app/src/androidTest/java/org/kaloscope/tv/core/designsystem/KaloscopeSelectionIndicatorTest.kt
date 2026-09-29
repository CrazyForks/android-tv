package org.kaloscope.tv.core.designsystem

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.kaloscope.tv.app.KaloscopeTheme
import org.kaloscope.tv.core.model.AccentColor
import org.kaloscope.tv.test.captureToImage

class KaloscopeSelectionIndicatorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun checkboxFadesInAndOutWithoutChangingItsBounds() {
        var selected by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        setCheckboxContent { selected }

        val indicator = composeRule.onNodeWithTag("test-checkbox-indicator")
        val bounds = indicator.getUnclippedBoundsInRoot()
        val restingGreen = fillGreen()
        assertEquals(20.dp, bounds.right - bounds.left)
        assertEquals(20.dp, bounds.bottom - bounds.top)
        composeRule.onNodeWithTag("test-checkbox-indicator-mark").assertDoesNotExist()

        composeRule.runOnIdle { selected = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(32)
        val selectingGreen = fillGreen()
        assertTrue("Checkbox fill should be partially visible", selectingGreen > restingGreen)
        assertTrue("Checkbox fill should still be transitioning", selectingGreen < 0xD9)
        assertEquals(bounds, indicator.getUnclippedBoundsInRoot())

        composeRule.mainClock.advanceTimeBy(120)
        val selectedGreen = fillGreen()
        assertEquals(0xD9.toFloat(), selectedGreen.toFloat(), 2f)
        composeRule.onNodeWithTag("test-checkbox-indicator-mark").assertExists()
        assertEquals(bounds, indicator.getUnclippedBoundsInRoot())

        composeRule.runOnIdle { selected = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(32)
        val deselectingGreen = fillGreen()
        assertTrue("Checkbox fill should fade out", deselectingGreen < selectedGreen)
        assertTrue("Checkbox fill should not disappear at once", deselectingGreen > restingGreen)
        composeRule.onNodeWithTag("test-checkbox-indicator-mark").assertExists()
        assertEquals(bounds, indicator.getUnclippedBoundsInRoot())

        composeRule.mainClock.advanceTimeBy(80)
        assertEquals(restingGreen, fillGreen())
        composeRule.onNodeWithTag("test-checkbox-indicator-mark").assertDoesNotExist()
        assertEquals(bounds, indicator.getUnclippedBoundsInRoot())
    }

    @Test
    fun initiallySelectedCheckboxIsFullyVisibleWithoutWaitingForAnimation() {
        composeRule.mainClock.autoAdvance = false
        setCheckboxContent { true }

        composeRule.onNodeWithTag("test-checkbox-indicator-mark").assertExists()
        assertEquals(0xD9.toFloat(), fillGreen().toFloat(), 2f)
    }

    private fun setCheckboxContent(selected: () -> Boolean) {
        composeRule.setContent {
            KaloscopeTheme(accentColor = AccentColor.Green) {
                CompositionLocalProvider(LocalContentColor provides OnBackground) {
                    Box(
                        modifier = Modifier.size(80.dp).background(Background),
                        contentAlignment = Alignment.Center,
                    ) {
                        KaloscopeSelectionIndicator(
                            type = KaloscopeSelectionIndicatorType.Checkbox,
                            selected = selected(),
                            testTagPrefix = "test",
                        )
                    }
                }
            }
        }
    }

    private fun fillGreen(): Int {
        val bitmap = composeRule.onNodeWithTag("test-checkbox-indicator")
            .captureToImage()
            .asAndroidBitmap()
        // Sample the fill between the outline and the checkmark.
        return AndroidColor.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 6))
    }
}
