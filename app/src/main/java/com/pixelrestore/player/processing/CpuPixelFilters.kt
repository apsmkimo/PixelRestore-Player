package com.pixelrestore.player.processing

import kotlin.math.roundToInt

/**
 * Integer ARGB filters used by the CPU fallback. They mirror the GPU stages
 * closely enough to be the same pipeline, at a capped resolution.
 */
object CpuPixelFilters {
    fun apply(pixels: IntArray, width: Int, height: Int, passes: List<FilterPass>): IntArray {
        var current = pixels
        var w = width
        var h = height
        for (pass in passes) {
            current = when (pass.type) {
                FilterType.DEBLOCK -> deblock(current, w, h, pass.strength, pass.secondary.toInt().coerceAtLeast(2))
                FilterType.EDGE_SMOOTH -> boxMix(current, w, h, pass.strength.coerceIn(0f, 1f))
                FilterType.DENOISE -> boxMix(current, w, h, pass.strength.coerceIn(0f, 1f))
                FilterType.SHARPEN -> sharpen(current, w, h, pass.strength)
                FilterType.CONTRAST -> contrast(current, pass.strength, pass.secondary)
                FilterType.SCALE_BILINEAR, FilterType.SCALE_BICUBIC, FilterType.BLIT -> current
                // Mosaic reconstruction is applied by MosaicRuntime before this loop.
                FilterType.MOSAIC_RECONSTRUCT, FilterType.MOSAIC_TEMPORAL -> current
            }
        }
        return current
    }

    fun deblock(src: IntArray, width: Int, height: Int, strength: Float, block: Int): IntArray {
        if (strength <= 0f) return src.copyOf()
        val dst = IntArray(src.size)
        val amount = strength.coerceIn(0f, 1f) * 0.65f
        for (y in 0 until height) {
            val modY = y % block
            val nearY = modY <= 1 || modY >= block - 1
            for (x in 0 until width) {
                val modX = x % block
                val nearX = modX <= 1 || modX >= block - 1
                val index = y * width + x
                if (!nearX && !nearY) {
                    dst[index] = src[index]
                    continue
                }
                val sampleX = when {
                    !nearX -> x
                    modX <= 1 -> (x - 2).coerceAtLeast(0)
                    else -> (x + 2).coerceAtMost(width - 1)
                }
                val sampleY = when {
                    !nearY -> y
                    modY <= 1 -> (y - 2).coerceAtLeast(0)
                    else -> (y + 2).coerceAtMost(height - 1)
                }
                dst[index] = mix(src[index], src[sampleY * width + sampleX], amount)
            }
        }
        return dst
    }

    fun boxMix(src: IntArray, width: Int, height: Int, amount: Float): IntArray {
        if (amount <= 0f) return src.copyOf()
        val dst = IntArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (ky in -1..1) {
                    val yy = y + ky
                    if (yy !in 0 until height) continue
                    for (kx in -1..1) {
                        val xx = x + kx
                        if (xx !in 0 until width) continue
                        val color = src[yy * width + xx]
                        r += (color shr 16) and 0xFF
                        g += (color shr 8) and 0xFF
                        b += color and 0xFF
                        count++
                    }
                }
                val blurred = argb(r / count, g / count, b / count)
                dst[y * width + x] = mix(src[y * width + x], blurred, amount)
            }
        }
        return dst
    }

    fun sharpen(src: IntArray, width: Int, height: Int, amount: Float): IntArray {
        if (amount <= 0f) return src.copyOf()
        val dst = IntArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val center = src[y * width + x]
                val north = src[(y - 1).coerceAtLeast(0) * width + x]
                val south = src[(y + 1).coerceAtMost(height - 1) * width + x]
                val east = src[y * width + (x + 1).coerceAtMost(width - 1)]
                val west = src[y * width + (x - 1).coerceAtLeast(0)]
                val blurR = (((north shr 16) and 0xFF) + ((south shr 16) and 0xFF) + ((east shr 16) and 0xFF) + ((west shr 16) and 0xFF)) / 4
                val blurG = (((north shr 8) and 0xFF) + ((south shr 8) and 0xFF) + ((east shr 8) and 0xFF) + ((west shr 8) and 0xFF)) / 4
                val blurB = ((north and 0xFF) + (south and 0xFF) + (east and 0xFF) + (west and 0xFF)) / 4
                val cr = (center shr 16) and 0xFF
                val cg = (center shr 8) and 0xFF
                val cb = center and 0xFF
                dst[y * width + x] = argb(
                    (cr + amount * (cr - blurR)).roundToInt().coerceIn(0, 255),
                    (cg + amount * (cg - blurG)).roundToInt().coerceIn(0, 255),
                    (cb + amount * (cb - blurB)).roundToInt().coerceIn(0, 255),
                )
            }
        }
        return dst
    }

    fun contrast(src: IntArray, contrastValue: Float, saturation: Float): IntArray {
        val dst = IntArray(src.size)
        for (i in src.indices) dst[i] = contrastPixel(src[i], contrastValue, saturation)
        return dst
    }

    fun contrastPixel(color: Int, contrastValue: Float, saturation: Float): Int {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        var rr = (r - 0.5f) * contrastValue + 0.5f
        var gg = (g - 0.5f) * contrastValue + 0.5f
        var bb = (b - 0.5f) * contrastValue + 0.5f
        val luma = rr * 0.2126f + gg * 0.7152f + bb * 0.0722f
        rr = luma + (rr - luma) * saturation
        gg = luma + (gg - luma) * saturation
        bb = luma + (bb - luma) * saturation
        return argb(
            (rr * 255f).roundToInt().coerceIn(0, 255),
            (gg * 255f).roundToInt().coerceIn(0, 255),
            (bb * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        return argb(
            (ar + (br - ar) * t).roundToInt(),
            (ag + (bg - ag) * t).roundToInt(),
            (ab + (bb - ab) * t).roundToInt(),
        )
    }

    private fun argb(r: Int, g: Int, b: Int): Int {
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
