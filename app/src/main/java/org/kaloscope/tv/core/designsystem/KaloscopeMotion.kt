package org.kaloscope.tv.core.designsystem

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.dp

object KaloscopeMotion {
    const val FocusMillis = 140
    const val ButtonFocusMillis = 120
    const val ButtonPressMillis = 60
    const val ButtonReleaseMillis = 140
    const val ButtonBlurMillis = 80
    const val GridCardFocusMillis = 160
    const val GridCardBlurMillis = 100
    // Preserve TV Material's press and release timing for grid cards.
    const val GridCardPressMillis = 120
    const val GridCardReleaseMillis = 300
    const val SidePanelEnterMillis = 180
    const val SidePanelScrimEnterMillis = 120
    const val PlayerControlEnterMillis = 160
    const val PlayerControlExitMillis = 120
    const val ReaderControlEnterMillis = 160
    const val ReaderControlExitMillis = 120
    const val ImageMillis = 150
    const val ContentMillis = 200
    const val BackgroundMillis = 350

    val SidePanelEnterOffset = 24.dp
    val ControlEasing: Easing = LinearOutSlowInEasing
}
