package com.pixelrestore.player.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Axis-aligned region in source pixels. [right] and [bottom] are exclusive.
 */
data class RoiBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun area(): Int = width * height
}

fun roiIou(a: RoiBox, b: RoiBox): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    val inter = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
    if (inter <= 0) return 0f
    val union = a.area() + b.area() - inter
    if (union <= 0) return 0f
    return inter.toFloat() / union.toFloat()
}

/**
 * Bounding box of flat mosaic cells. A full-frame lattice returns the whole frame.
 * Natural texture stays outside the box when only part of the picture is pixelated.
 */
object RoiExtractor {
    private const val FLAT_MAD = 2.0f
    private const val MIN_CELLS = 4
    private const val FULL_FRAME_FRACTION = 0.92f

    fun mosaicRegion(argb: IntArray, width: Int, height: Int, grid: MosaicGrid): RoiBox? {
        if (!grid.usable || width < 2 || height < 2 || argb.size < width * height) return null
        val bw = grid.blockWidth
        val bh = grid.blockHeight
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var marked = 0
        var total = 0
        var y = grid.offsetY.coerceIn(0, height - 1)
        while (y + bh <= height) {
            var x = grid.offsetX.coerceIn(0, width - 1)
            while (x + bw <= width) {
                total++
                if (blockIsFlat(argb, width, x, y, bw, bh)) {
                    marked++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x + bw > maxX) maxX = x + bw
                    if (y + bh > maxY) maxY = y + bh
                }
                x += bw
            }
            y += bh
        }
        if (total == 0 || marked < MIN_CELLS) return null
        if (marked.toFloat() / total.toFloat() >= FULL_FRAME_FRACTION) {
            return RoiBox(0, 0, width, height)
        }
        return RoiBox(
            left = (minX - bw).coerceAtLeast(0),
            top = (minY - bh).coerceAtLeast(0),
            right = (maxX + bw).coerceAtMost(width),
            bottom = (maxY + bh).coerceAtMost(height),
        )
    }

    private fun blockIsFlat(
        argb: IntArray,
        width: Int,
        originX: Int,
        originY: Int,
        blockWidth: Int,
        blockHeight: Int,
    ): Boolean {
        var sum = 0
        var count = 0
        val stepX = max(1, blockWidth / 4)
        val stepY = max(1, blockHeight / 4)
        var y = originY
        while (y < originY + blockHeight) {
            var x = originX
            val row = y * width
            while (x < originX + blockWidth) {
                sum += luma(argb[row + x])
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val mean = sum.toFloat() / count.toFloat()
        var dev = 0f
        y = originY
        while (y < originY + blockHeight) {
            var x = originX
            val row = y * width
            while (x < originX + blockWidth) {
                dev += kotlin.math.abs(luma(argb[row + x]) - mean)
                x += stepX
            }
            y += stepY
        }
        return dev / count <= FLAT_MAD
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 54 + g * 183 + b * 19) shr 8
    }
}

/**
 * Keeps one mosaic ROI across frames. A high-overlap box is smoothed.
 * A box that does not overlap is treated as a new region.
 * A missing detection keeps the last box so one weak frame does not drop the tile.
 */
class RoiTracker(
    private val keep: Float = 0.65f,
    private val minIou: Float = 0.15f,
) {
    var box: RoiBox? = null
        private set

    fun update(candidate: RoiBox?): RoiBox? {
        val prev = box
        if (candidate == null) return prev
        if (prev == null || roiIou(prev, candidate) < minIou) {
            box = candidate
            return candidate
        }
        val take = 1f - keep
        box = RoiBox(
            left = blend(prev.left, candidate.left, keep, take),
            top = blend(prev.top, candidate.top, keep, take),
            right = blend(prev.right, candidate.right, keep, take),
            bottom = blend(prev.bottom, candidate.bottom, keep, take),
        )
        return box
    }

    fun clear() {
        box = null
    }

    private fun blend(previous: Int, next: Int, keepWeight: Float, takeWeight: Float): Int {
        return (previous * keepWeight + next * takeWeight).roundToInt()
    }
}
