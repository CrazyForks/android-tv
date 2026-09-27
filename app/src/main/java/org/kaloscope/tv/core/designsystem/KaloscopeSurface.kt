package org.kaloscope.tv.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface

enum class KaloscopeFocusSurfaceVariant {
    Default,
    GridCard,
}

@Composable
fun KaloscopeFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape,
    containerColor: Color = Color.Transparent,
    selectedContainerColor: Color? = null,
    focusedContainerColor: Color = PanelElevated,
    focusScale: Float = 1.03f,
    focusScaleEdgeClearance: Dp? = null,
    variant: KaloscopeFocusSurfaceVariant = KaloscopeFocusSurfaceVariant.Default,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val restingColor = if (selected) {
        selectedContainerColor ?: LocalAccentPalette.current.panelSelected
    } else {
        containerColor
    }
    val baseModifier = modifier.semantics { this.selected = selected }
    val focusModifier = focusScaleEdgeClearance?.let { edgeClearance ->
        baseModifier
            .focusable(
                enabled = enabled,
                interactionSource = interactionSource,
            )
            .reserveFocusedScaleHeight(
                focusScale = focusScale,
                edgeClearance = edgeClearance,
            )
            // Surface adds its own focus target after the supplied modifier. Keep that
            // inner target out of traversal so relocation uses these reserved bounds.
            .focusProperties { canFocus = false }
    } ?: baseModifier
    val gridAnimation = if (variant == KaloscopeFocusSurfaceVariant.GridCard) {
        rememberGridCardAnimation(interactionSource, enabled, focusScale)
    } else {
        null
    }
    val surfaceModifier = if (gridAnimation != null) {
        // Animate only the content bounds; keep the reserved focus bounds unchanged.
        focusModifier
            .graphicsLayer {
                scaleX = gridAnimation.scale
                scaleY = gridAnimation.scale
            }
            .border(
                width = BrowseLayoutTokens.GridCardFocusBorderWidth,
                color = ControlFocused.copy(alpha = gridAnimation.focusFraction),
                shape = shape,
            )
    } else {
        focusModifier
    }
    val animatedContainerColor = gridAnimation?.let {
        lerp(restingColor, focusedContainerColor, it.focusFraction)
    }
    Surface(
        onClick = onClick,
        modifier = surfaceModifier,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = animatedContainerColor ?: restingColor,
            focusedContainerColor = animatedContainerColor ?: focusedContainerColor,
            contentColor = OnBackground,
            focusedContentColor = OnBackground,
            disabledContainerColor = restingColor.copy(alpha = 0.45f),
            disabledContentColor = Muted,
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = if (gridAnimation == null) focusScale else 1f,
            disabledScale = 1f,
            focusedDisabledScale = 1f,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedDisabledBorder = Border(
                border = BorderStroke(2.dp, Color.White),
                shape = shape,
            ),
        ),
        interactionSource = interactionSource,
        content = content,
    )
}

private data class GridCardAnimation(
    val focusFraction: Float,
    val scale: Float,
)

@Composable
private fun rememberGridCardAnimation(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    focusScale: Float,
): GridCardAnimation {
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val visiblyFocused = enabled && focused
    val focusDuration = if (visiblyFocused) {
        KaloscopeMotion.GridCardFocusMillis
    } else {
        KaloscopeMotion.GridCardBlurMillis
    }
    val focusFraction by animateFloatAsState(
        targetValue = if (visiblyFocused) 1f else 0f,
        animationSpec = tween(focusDuration, easing = KaloscopeMotion.ControlEasing),
        label = "grid-card-focus",
    )
    var wasPressed by remember { mutableStateOf(false) }
    // A release keeps its duration while the focus animation recomposes this control.
    val scaleDuration = remember(visiblyFocused, pressed) {
        when {
            !visiblyFocused -> KaloscopeMotion.GridCardBlurMillis
            pressed -> KaloscopeMotion.GridCardPressMillis
            wasPressed -> KaloscopeMotion.GridCardReleaseMillis
            else -> KaloscopeMotion.GridCardFocusMillis
        }
    }
    SideEffect { wasPressed = visiblyFocused && pressed }
    val scale by animateFloatAsState(
        targetValue = if (visiblyFocused && !pressed) focusScale else 1f,
        animationSpec = tween(scaleDuration, easing = KaloscopeMotion.ControlEasing),
        label = "grid-card-scale",
    )
    return GridCardAnimation(focusFraction = focusFraction, scale = scale)
}
