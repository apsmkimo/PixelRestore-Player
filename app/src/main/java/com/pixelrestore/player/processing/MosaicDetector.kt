package com.pixelrestore.player.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a regular pixelation lattice from luma samples.
 *
 * A mosaic block is flat inside and discontinuous on its border. For each candidate
 * square size, horizontal and vertical offsets are scored separately: mean absolute
 * difference across the candidate border, divided by the same difference inside the
 * block. The search does not assume one fixed block size.
 *
 * [manualBlock] restricts the size (2, 4, 8, 16, or 32) but still estimates the phase.
 */
object MosaicDetector {
    val candidateSizes: IntArray = intArrayOf(2, 3, 4, 6, 8, 10, 12, 16, 24, 32)

    // SMCPKG_SUPPORT>>>Cursor027
    // private const val MIN_RATIO = 1.5
    // private const val MIN_SCORE = 1.85f
    // Soft or blurred block edges still beat the interior, but the ratio sits
    // near 1.2 instead of 2+. Harmonics (16, 32) can outscore the true cell
    // once the edge is smeared, so a near-best smaller size wins.
    private const val MIN_RATIO = 1.15
    private const val MIN_SCORE = 1.15f
    private const val HARMONIC_KEEP = 0.85f
    // SMCPKG_SUPPORT<<<Cursor028

    fun detect(argb: IntArray, width: Int, height: Int, manualBlock: Int?): MosaicGrid {
        if (width < 8 || height < 8 || argb.size < width * height) return MosaicGrid.UNDETECTED
        val luma = IntArray(width * height)
        for (i in luma.indices) {
            luma[i] = lumaOf(argb[i])
        }
        return detectLuma(luma, width, height, manualBlock)
    }

    fun detectLuma(luma: IntArray, width: Int, height: Int, manualBlock: Int?): MosaicGrid {
        if (width < 8 || height < 8 || luma.size < width * height) return MosaicGrid.UNDETECTED
        val sizes = if (manualBlock != null && manualBlock >= 2) {
            intArrayOf(manualBlock)
        } else {
            candidateSizes
        }
        // SMCPKG_SUPPORT>>>Cursor029
        // var best: MosaicGrid? = null
        // for (size in sizes) {
        //     if (size < 2 || size > width / 3 || size > height / 3) continue
        //     val horizontal = bestPhase(luma, width, height, size, vertical = false)
        //     val vertical = bestPhase(luma, width, height, size, vertical = true)
        //     val ratioH = horizontal.second
        //     val ratioV = vertical.second
        //     val score = min(ratioH, ratioV).toFloat()
        //     val grid = MosaicGrid(..., detected = manual || ratio >= MIN_RATIO && score >= MIN_SCORE)
        //     if (manualBlock != null) return grid
        //     if (best == null || grid.confidence > best.confidence) best = grid
        // }
        // val chosen = best ?: return MosaicGrid.UNDETECTED
        // return if (chosen.detected) chosen else MosaicGrid.UNDETECTED.copy(confidence = chosen.confidence)
        var bestAny: MosaicGrid? = null
        val passing = ArrayList<MosaicGrid>(sizes.size)
        for (size in sizes) {
            if (size < 2 || size > width / 3 || size > height / 3) continue
            val horizontal = bestPhase(luma, width, height, size, vertical = false)
            val vertical = bestPhase(luma, width, height, size, vertical = true)
            val ratioH = horizontal.second
            val ratioV = vertical.second
            // Both axes must agree. The minimum rejects a period that is strong
            // on only one axis.
            val score = min(ratioH, ratioV).toFloat()
            val passes = ratioH >= MIN_RATIO && ratioV >= MIN_RATIO && score >= MIN_SCORE
            val grid = MosaicGrid(
                blockWidth = size,
                blockHeight = size,
                offsetX = horizontal.first,
                offsetY = vertical.first,
                confidence = score,
                detected = manualBlock != null || passes,
                manual = manualBlock != null,
            )
            if (manualBlock != null) return grid
            val currentBest = bestAny
            if (currentBest == null || grid.confidence > currentBest.confidence) bestAny = grid
            if (passes) passing.add(grid)
        }
        if (passing.isEmpty()) {
            return MosaicGrid.UNDETECTED.copy(confidence = bestAny?.confidence ?: 0f)
        }
        val peak = passing.maxOf { it.confidence }
        val floor = peak * HARMONIC_KEEP
        return passing
            .filter { it.confidence >= floor }
            .minBy { it.blockWidth }
        // SMCPKG_SUPPORT<<<Cursor030
    }

    /**
     * @return phase in 0 until [size], and boundary/interior ratio (higher means a cleaner grid).
     */
    private fun bestPhase(
        luma: IntArray,
        width: Int,
        height: Int,
        size: Int,
        vertical: Boolean,
    ): Pair<Int, Double> {
        var bestOffset = 0
        var bestRatio = 0.0
        val yStep = max(1, height / 40)
        val xStep = max(1, width / 40)
        for (offset in 0 until size) {
            var boundary = 0.0
            var interior = 0.0
            var boundaryCount = 0
            var interiorCount = 0
            if (!vertical) {
                var y = 0
                while (y < height) {
                    val row = y * width
                    var x = 0
                    while (x < width - 1) {
                        val diff = abs(luma[row + x] - luma[row + x + 1]).toDouble()
                        if (floorMod(x + 1 - offset, size) == 0) {
                            boundary += diff
                            boundaryCount++
                        } else {
                            interior += diff
                            interiorCount++
                        }
                        x++
                    }
                    y += yStep
                }
            } else {
                var x = 0
                while (x < width) {
                    var y = 0
                    while (y < height - 1) {
                        val diff = abs(luma[y * width + x] - luma[(y + 1) * width + x]).toDouble()
                        if (floorMod(y + 1 - offset, size) == 0) {
                            boundary += diff
                            boundaryCount++
                        } else {
                            interior += diff
                            interiorCount++
                        }
                        y++
                    }
                    x += xStep
                }
            }
            val ratio = (boundary / max(boundaryCount, 1)) / ((interior / max(interiorCount, 1)) + 1.0)
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestOffset = offset
            }
        }
        return bestOffset to bestRatio
    }

    private fun lumaOf(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 54 + g * 183 + b * 19) shr 8
    }

    private fun floorMod(value: Int, size: Int): Int {
        val mod = value % size
        return if (mod < 0) mod + size else mod
    }
}
