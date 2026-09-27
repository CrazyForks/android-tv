package org.kaloscope.tv.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

enum class KaloscopeSidePanelSide {
    Start,
    End,
}

enum class KaloscopeSidePanelSize(val width: Dp) {
    Compact(400.dp),
    Standard(500.dp),
}

data class KaloscopeSidePanelPalette(
    val panelColor: Color,
    val textColor: Color,
    val mutedColor: Color,
    val controlContentColor: Color = textColor,
    val panelAlpha: Float = 0.99f,
)

@Composable
fun KaloscopeSidePanel(
    title: String,
    palette: KaloscopeSidePanelPalette,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    side: KaloscopeSidePanelSide = KaloscopeSidePanelSide.End,
    size: KaloscopeSidePanelSize = KaloscopeSidePanelSize.Standard,
    description: String? = null,
    dismissEnabled: Boolean = true,
    trapFocus: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val panelProgress = remember { Animatable(0f) }
    val scrimProgress = remember { Animatable(0f) }
    val layoutDirection = LocalLayoutDirection.current
    val entersFromLeft = when (side) {
        KaloscopeSidePanelSide.Start -> layoutDirection == LayoutDirection.Ltr
        KaloscopeSidePanelSide.End -> layoutDirection == LayoutDirection.Rtl
    }
    // Animate drawing only so initial focus and Back do not wait for the entrance.
    LaunchedEffect(Unit) {
        launch {
            scrimProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    KaloscopeMotion.SidePanelScrimEnterMillis,
                    easing = KaloscopeMotion.ControlEasing,
                ),
            )
        }
        panelProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                KaloscopeMotion.SidePanelEnterMillis,
                easing = KaloscopeMotion.ControlEasing,
            ),
        )
    }
    BackHandler(enabled = dismissEnabled, onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = 0.58f * scrimProgress.value))
            },
        contentAlignment = when (side) {
            KaloscopeSidePanelSide.Start -> Alignment.CenterStart
            KaloscopeSidePanelSide.End -> Alignment.CenterEnd
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .width(size.width)
                .graphicsLayer {
                    alpha = panelProgress.value
                    val offset = KaloscopeMotion.SidePanelEnterOffset.toPx() *
                        (1f - panelProgress.value)
                    translationX = if (entersFromLeft) -offset else offset
                }
                .background(palette.panelColor.copy(alpha = palette.panelAlpha))
                .padding(horizontal = 28.dp, vertical = 32.dp)
                .then(
                    if (trapFocus) {
                        Modifier
                            .focusGroup()
                            .focusProperties {
                                onExit = { cancelFocusChange() }
                            }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(
                text = title,
                color = palette.textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            description?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    color = palette.mutedColor,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(if (description == null) 20.dp else 18.dp))
            CompositionLocalProvider(
                LocalKaloscopeControlRestingContentColor provides palette.controlContentColor,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    content = content,
                )
                footer?.let {
                    Spacer(Modifier.height(8.dp))
                    it()
                }
            }
        }
    }
}
