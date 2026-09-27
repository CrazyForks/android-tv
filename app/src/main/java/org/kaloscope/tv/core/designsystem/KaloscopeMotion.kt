package org.kaloscope.tv.core.designsystem

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing

object KaloscopeMotion {
    const val FocusMillis = 140
    const val ButtonFocusMillis = 120
    const val ButtonPressMillis = 60
    const val ButtonReleaseMillis = 140
    const val ButtonBlurMillis = 80
    const val ImageMillis = 150
    const val ContentMillis = 200
    const val BackgroundMillis = 350

    val ControlEasing: Easing = LinearOutSlowInEasing
}
