package org.kaloscope.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class OffsetTextRendererTest {
    @Test
    fun `offset changes reset text once and preserve frame time`() {
        val clock = SubtitleClock()
        val delegate = RecordingTextRenderer()
        val renderer = OffsetTextRenderer(delegate.instance, clock)

        renderer.render(10_000_000L, 1_000L)
        clock.setOffsetSeconds(0.5f)
        renderer.render(11_000_000L, 2_000L)
        clock.setOffsetSeconds(0.5f)
        renderer.render(12_000_000L, 3_000L)
        clock.setOffsetSeconds(-0.5f)
        renderer.render(13_000_000L, 4_000L)

        assertEquals(
            listOf(
                "render:10000000:1000",
                "reset:10500000:false",
                "render:10500000:2000",
                "render:11500000:3000",
                "reset:13500000:false",
                "render:13500000:4000",
            ),
            delegate.commands,
        )
    }

    @Test
    fun `offset changed during reset is applied on the next render`() {
        val clock = SubtitleClock()
        val delegate = RecordingTextRenderer(
            // Reproduce a UI offset update while the playback thread resets text state.
            onReset = { clock.setOffsetSeconds(1f) },
        )
        val renderer = OffsetTextRenderer(delegate.instance, clock)

        clock.setOffsetSeconds(0.5f)
        renderer.render(10_000_000L, 1_000L)
        renderer.render(10_000_000L, 2_000L)
        renderer.render(10_000_000L, 3_000L)

        assertEquals(
            listOf(
                "reset:9500000:false",
                "render:9500000:1000",
                "reset:9000000:false",
                "render:9000000:2000",
                "render:9000000:3000",
            ),
            delegate.commands,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class RecordingTextRenderer(
    private val onReset: () -> Unit = {},
) {
    val commands = mutableListOf<String>()

    val instance = Proxy.newProxyInstance(
        Renderer::class.java.classLoader,
        arrayOf(Renderer::class.java),
    ) { _, method, arguments ->
        val args = checkNotNull(arguments)
        when (method.name) {
            "resetPosition" -> {
                commands += "reset:${args[0]}:${args[1]}"
                onReset()
            }

            "render" -> commands += "render:${args[0]}:${args[1]}"
            else -> error("Unexpected renderer call: ${method.name}")
        }
        null
    } as Renderer
}
