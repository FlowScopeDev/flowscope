package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Decides whether the decoder's frame buffer may be reused.
 *
 * If `Image.makeRaster` copies the pixels, the producer can hand the same array back to a pool the
 * moment the call returns. If it wraps the array instead, reuse would corrupt frames already
 * handed to the UI, and pooling is unsafe.
 */
class RasterCopyTest {

    @Test
    fun `makeRaster copies the source array`() {
        val info = ImageInfo(2, 1, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val pixels = byteArrayOf(
            1, 2, 3, 4.toByte(),
            5, 6, 7, 8.toByte(),
        )
        val image = Image.makeRaster(info, pixels, 2 * 4)

        // Mutate the source after the image exists.
        pixels.fill(0)

        val readBack = Bitmap().apply { allocPixels(info) }
        image.readPixels(readBack)
        val first = readBack.getColor(0, 0)
        val second = readBack.getColor(1, 0)

        // BGRA bytes 1,2,3,4 -> blue=1 green=2 red=3 alpha=4 (opaque forces alpha, so check RGB).
        assertEquals(3, (first shr 16) and 0xFF, "red channel survived the source being zeroed")
        assertEquals(2, (first shr 8) and 0xFF, "green channel survived")
        assertEquals(1, first and 0xFF, "blue channel survived")
        assertEquals(7, (second shr 16) and 0xFF, "second pixel survived")
    }
}
