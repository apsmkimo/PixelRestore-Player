package com.pixelrestore.player.processing

import kotlin.math.roundToInt

/**
 * Packs one ROI into the fixed SESR-M5 INT8 tensor and blends the 2× result back.
 *
 * The bundled graph is float32 NCHW 1×3×512×512 → 1×3×1024×1024.
 * Values are about 0..255. The model card's 256×256 figure is the training tile;
 * this ONNX file does not take a 256 input.
 */
object SesrTiles {
    const val INPUT = 512
    const val OUTPUT = 1024
    const val SCALE = 2
    const val ASSET = "models/sesr_m5_int8.onnx"

    fun inputFloats(): Int = 3 * INPUT * INPUT

    fun outputFloats(): Int = 3 * OUTPUT * OUTPUT

    fun packRgbNchw(
        argb: IntArray,
        width: Int,
        height: Int,
        roi: RoiBox,
        dest: FloatArray,
    ) {
        require(dest.size >= inputFloats())
        val plane = INPUT * INPUT
        val roiW = roi.width.coerceAtLeast(1)
        val roiH = roi.height.coerceAtLeast(1)
        for (y in 0 until INPUT) {
            val sy = roi.top + (y + 0.5f) * roiH / INPUT - 0.5f
            val y0 = sy.toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            val row0 = y0 * width
            val row1 = y1 * width
            for (x in 0 until INPUT) {
                val sx = roi.left + (x + 0.5f) * roiW / INPUT - 0.5f
                val x0 = sx.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val c00 = argb[row0 + x0]
                val c10 = argb[row0 + x1]
                val c01 = argb[row1 + x0]
                val c11 = argb[row1 + x1]
                val index = y * INPUT + x
                dest[index] = bilerp(channel(c00, 16), channel(c10, 16), channel(c01, 16), channel(c11, 16), fx, fy)
                dest[plane + index] = bilerp(channel(c00, 8), channel(c10, 8), channel(c01, 8), channel(c11, 8), fx, fy)
                dest[2 * plane + index] = bilerp(channel(c00, 0), channel(c10, 0), channel(c01, 0), channel(c11, 0), fx, fy)
            }
        }
    }

    /**
     * Samples the 1024×1024 NCHW output back onto [roi] and leaves the rest of [source] unchanged.
     */
    fun blend(
        source: IntArray,
        width: Int,
        height: Int,
        roi: RoiBox,
        nchw: FloatArray,
    ): IntArray {
        val out = source.copyOf()
        if (nchw.size < outputFloats() || roi.width <= 0 || roi.height <= 0) return out
        val plane = OUTPUT * OUTPUT
        val left = roi.left.coerceIn(0, width)
        val top = roi.top.coerceIn(0, height)
        val right = roi.right.coerceIn(left, width)
        val bottom = roi.bottom.coerceIn(top, height)
        val roiW = (right - left).coerceAtLeast(1)
        val roiH = (bottom - top).coerceAtLeast(1)
        for (y in top until bottom) {
            val sy = (y - top + 0.5f) * OUTPUT / roiH - 0.5f
            val y0 = sy.toInt().coerceIn(0, OUTPUT - 1)
            val y1 = (y0 + 1).coerceAtMost(OUTPUT - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            val row = y * width
            for (x in left until right) {
                val sx = (x - left + 0.5f) * OUTPUT / roiW - 0.5f
                val x0 = sx.toInt().coerceIn(0, OUTPUT - 1)
                val x1 = (x0 + 1).coerceAtMost(OUTPUT - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val i00 = y0 * OUTPUT + x0
                val i10 = y0 * OUTPUT + x1
                val i01 = y1 * OUTPUT + x0
                val i11 = y1 * OUTPUT + x1
                val r = bilerp(nchw[i00], nchw[i10], nchw[i01], nchw[i11], fx, fy)
                val g = bilerp(nchw[plane + i00], nchw[plane + i10], nchw[plane + i01], nchw[plane + i11], fx, fy)
                val b = bilerp(nchw[2 * plane + i00], nchw[2 * plane + i10], nchw[2 * plane + i01], nchw[2 * plane + i11], fx, fy)
                val src = out[row + x]
                out[row + x] = (src and 0xFF000000.toInt()) or
                    (clampByte(r) shl 16) or
                    (clampByte(g) shl 8) or
                    clampByte(b)
            }
        }
        return out
    }

    private fun channel(color: Int, shift: Int): Float = ((color shr shift) and 0xFF).toFloat()

    private fun bilerp(c00: Float, c10: Float, c01: Float, c11: Float, fx: Float, fy: Float): Float {
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return top + (bottom - top) * fy
    }

    private fun clampByte(value: Float): Int = value.roundToInt().coerceIn(0, 255)
}

/** Runs the 512→1024 network. Returning null keeps the frame unchanged. */
fun interface TileEnhancer {
    fun enhance(nchw512: FloatArray): FloatArray?
}
