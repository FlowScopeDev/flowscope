package dev.flowscope.ide

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertSame

class DeviceStateTest {

    @Test
    fun `live image prefers embedded scrcpy frame over fallback screenshot`() {
        val screenshot = ImageBitmap(720, 1600)
        val streamFrame = ImageBitmap(486, 1080)
        val state = DeviceState().apply {
            this.screenshot = screenshot
            embeddedFrame = streamFrame
        }

        assertSame(streamFrame, state.liveImage)
    }

    @Test
    fun `live image falls back to screenshot without a stream frame`() {
        val screenshot = ImageBitmap(720, 1600)
        val state = DeviceState().apply { this.screenshot = screenshot }

        assertSame(screenshot, state.liveImage)
    }
}
