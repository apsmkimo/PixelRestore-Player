package com.pixelrestore.player.player

import android.media.Image
import kotlin.math.max
import kotlin.math.roundToInt

internal class ArgbImage(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

internal object YuvFrames {
    fun toArgb(image: Image, maxEdge: Int): ArgbImage {
        if (image.planes.size < 3) {
            throw IllegalArgumentException("Expected a YUV image with 3 planes")
        }
        val width = image.width
        val height = image.height
        val step = max(1, max(width, height) / maxEdge.coerceAtLeast(2))
        val outW = (width / step).coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val outH = (height / step).coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yPos = yBuf.position()
        val uPos = uBuf.position()
        val vPos = vBuf.position()
        val pixels = IntArray(outW * outH)
        var index = 0
        var y = 0
        while (y < outH) {
            val sy = y * step
            val yRow = yPos + sy * yPlane.rowStride
            val uvRowU = uPos + (sy / 2) * uPlane.rowStride
            val uvRowV = vPos + (sy / 2) * vPlane.rowStride
            var x = 0
            while (x < outW) {
                val sx = x * step
                val yValue = yBuf.get(yRow + sx * yPlane.pixelStride).toInt() and 0xFF
                val uvx = sx / 2
                val uValue = (uBuf.get(uvRowU + uvx * uPlane.pixelStride).toInt() and 0xFF) - 128
                val vValue = (vBuf.get(uvRowV + uvx * vPlane.pixelStride).toInt() and 0xFF) - 128
                val r = (yValue + 1.402f * vValue).roundToInt().coerceIn(0, 255)
                val g = (yValue - 0.344136f * uValue - 0.714136f * vValue).roundToInt().coerceIn(0, 255)
                val b = (yValue + 1.772f * uValue).roundToInt().coerceIn(0, 255)
                pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                index++
                x++
            }
            y++
        }
        return ArgbImage(pixels, outW, outH)
    }
}
