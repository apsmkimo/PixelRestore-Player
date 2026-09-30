package com.pixelrestore.player.processing

import com.pixelrestore.player.settings.QualityLevel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mosaic-aware reconstruction. Each flat block is one low-resolution sample (its center).
 * Output pixels are estimated from neighboring block colors:
 * Low is bilinear on that lattice plus a light unsharp mask.
 * Medium adds gradient-directed interpolation, a seam deblock on weak edges, temporal
 * block matching, and sharpen.
 * High adds a Catmull-Rom sample of the block lattice in smooth areas, a wider motion
 * search, and adaptive sharpen.
 *
 * This is an estimate from spatial and temporal samples. It does not restore the original pixels.
 */
object MosaicReconstruction {
    data class Result(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val grid: MosaicGrid,
        val processMs: Float,
    )

    fun process(
        source: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        quality: QualityLevel,
        debugView: MosaicDebugView,
        compareOriginal: Boolean,
        previousSource: IntArray?,
        previousReconstruction: IntArray?,
        previousWidth: Int,
        previousHeight: Int,
    ): Result {
        val start = System.nanoTime()
        val output = when {
            compareOriginal || debugView == MosaicDebugView.ORIGINAL || !grid.usable ->
                source.copyOf()
            debugView == MosaicDebugView.GRID -> drawGrid(source, width, height, grid)
            else -> reconstruct(
                source = source,
                width = width,
                height = height,
                grid = grid,
                quality = quality,
                includeFinish = debugView == MosaicDebugView.FINAL,
                previousSource = previousSource,
                previousReconstruction = previousReconstruction,
                previousWidth = previousWidth,
                previousHeight = previousHeight,
            )
        }
        val ms = (System.nanoTime() - start) / 1_000_000f
        return Result(output, width, height, grid, ms)
    }

    fun pixelateAverage(
        source: IntArray,
        width: Int,
        height: Int,
        block: Int,
        offsetX: Int,
        offsetY: Int,
    ): IntArray {
        val dst = source.copyOf()
        var by = offsetY
        while (by < height) {
            val y0 = max(by, 0)
            val y1 = min(by + block, height)
            var bx = offsetX
            while (bx < width) {
                val x0 = max(bx, 0)
                val x1 = min(bx + block, width)
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val color = source[row + x]
                        r += (color shr 16) and 0xFF
                        g += (color shr 8) and 0xFF
                        b += color and 0xFF
                        count++
                    }
                }
                if (count > 0) {
                    val flat = argb(r / count, g / count, b / count)
                    for (y in y0 until y1) {
                        val row = y * width
                        for (x in x0 until x1) dst[row + x] = flat
                    }
                }
                bx += block
            }
            by += block
        }
        return dst
    }

    /**
     * Mean luma jump on the mosaic border minus the mean jump inside blocks.
     * Larger means the square lattice is more visible.
     */
    fun boundaryStrength(
        pixels: IntArray,
        width: Int,
        height: Int,
        block: Int,
        offsetX: Int,
        offsetY: Int,
    ): Double {
        if (block < 2) return 0.0
        var boundary = 0.0
        var interior = 0.0
        var boundaryCount = 0
        var interiorCount = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width - 1) {
                val diff = abs(lumaOf(pixels[row + x]) - lumaOf(pixels[row + x + 1])).toDouble()
                if (floorMod(x + 1 - offsetX, block) == 0) {
                    boundary += diff
                    boundaryCount++
                } else {
                    interior += diff
                    interiorCount++
                }
            }
        }
        for (x in 0 until width) {
            for (y in 0 until height - 1) {
                val diff = abs(lumaOf(pixels[y * width + x]) - lumaOf(pixels[(y + 1) * width + x])).toDouble()
                if (floorMod(y + 1 - offsetY, block) == 0) {
                    boundary += diff
                    boundaryCount++
                } else {
                    interior += diff
                    interiorCount++
                }
            }
        }
        val boundaryMean = boundary / max(boundaryCount, 1)
        val interiorMean = interior / max(interiorCount, 1)
        return boundaryMean - interiorMean
    }

    fun psnr(reference: IntArray, sample: IntArray): Double {
        val n = min(reference.size, sample.size)
        if (n == 0) return 0.0
        var error = 0.0
        for (i in 0 until n) {
            val d = (lumaOf(reference[i]) - lumaOf(sample[i])).toDouble()
            error += d * d
        }
        val mse = error / n
        if (mse <= 1e-9) return 99.0
        return 10.0 * ln((255.0 * 255.0) / mse) / ln(10.0)
    }

    /** Global SSIM on luma. Windowed SSIM is unnecessary for the synthetic fixtures. */
    fun ssim(reference: IntArray, sample: IntArray): Double {
        val n = min(reference.size, sample.size)
        if (n == 0) return 0.0
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until n) {
            sumA += lumaOf(reference[i])
            sumB += lumaOf(sample[i])
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var varA = 0.0
        var varB = 0.0
        var cov = 0.0
        for (i in 0 until n) {
            val da = lumaOf(reference[i]) - meanA
            val db = lumaOf(sample[i]) - meanB
            varA += da * da
            varB += db * db
            cov += da * db
        }
        varA /= n
        varB /= n
        cov /= n
        val c1 = (0.01 * 255) * (0.01 * 255)
        val c2 = (0.03 * 255) * (0.03 * 255)
        val numerator = (2 * meanA * meanB + c1) * (2 * cov + c2)
        val denominator = (meanA * meanA + meanB * meanB + c1) * (varA + varB + c2)
        if (denominator == 0.0) return 1.0
        return numerator / denominator
    }

    /**
     * Width, in pixels, of the gray ramp across a vertical step.
     * Counts luma samples on [row] between [dark] and [bright].
     */
    fun transitionWidth(pixels: IntArray, width: Int, row: Int, dark: Int, bright: Int): Int {
        var count = 0
        val start = row * width
        for (x in 0 until width) {
            val y = lumaOf(pixels[start + x])
            if (y in (dark + 1) until bright) count++
        }
        return count
    }

    /**
     * Block match on representative block colors. Returns dx, dy in block units and the mean SAD.
     */
    fun matchBlock(
        current: IntArray,
        width: Int,
        height: Int,
        previous: IntArray,
        previousWidth: Int,
        previousHeight: Int,
        grid: MosaicGrid,
        blockX: Int,
        blockY: Int,
        radius: Int,
    ): MotionMatch {
        if (!grid.usable || previousWidth != width || previousHeight != height || previous.size < width * height) {
            return MotionMatch(0, 0, Float.MAX_VALUE)
        }
        var bestDx = 0
        var bestDy = 0
        var bestSad = Float.MAX_VALUE
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sad = neighborhoodSad(current, width, height, previous, grid, blockX, blockY, dx, dy)
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return MotionMatch(bestDx, bestDy, bestSad)
    }

    private fun reconstruct(
        source: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        quality: QualityLevel,
        includeFinish: Boolean,
        previousSource: IntArray?,
        previousReconstruction: IntArray?,
        previousWidth: Int,
        previousHeight: Int,
    ): IntArray {
        val bw = grid.blockWidth
        val bh = grid.blockHeight
        val spatial = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val bilinear = latticeSample(source, width, height, grid, x, y, bicubic = false)
                val color = when (quality) {
                    QualityLevel.LOW -> bilinear
                    QualityLevel.MEDIUM -> directional(source, width, height, grid, x, y, bilinear)
                    QualityLevel.HIGH -> {
                        val directed = directional(source, width, height, grid, x, y, bilinear)
                        val cubic = latticeSample(source, width, height, grid, x, y, bicubic = true)
                        val edge = edgeStrength(source, width, height, grid, x, y)
                        val keepEdge = ((edge - 0.08f) / 0.22f).coerceIn(0f, 1f)
                        mixColor(cubic, directed, keepEdge)
                    }
                }
                spatial[row + x] = deblockIfWeak(color, bilinear, source, width, height, grid, x, y, quality)
            }
        }
        val finished = if (!includeFinish) {
            spatial
        } else {
            val sharpened = sharpenAdaptive(spatial, width, height, quality, bw, bh, grid)
            if (quality == QualityLevel.LOW || previousSource == null || previousReconstruction == null) {
                sharpened
            } else {
                temporalBlend(
                    spatial = sharpened,
                    source = source,
                    width = width,
                    height = height,
                    previousSource = previousSource,
                    previousReconstruction = previousReconstruction,
                    previousWidth = previousWidth,
                    previousHeight = previousHeight,
                    grid = grid,
                    quality = quality,
                )
            }
        }
        return finished
    }

    private fun latticeSample(
        source: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        x: Int,
        y: Int,
        bicubic: Boolean,
    ): Int {
        val relX = (x - grid.offsetX).toFloat() / grid.blockWidth - 0.5f
        val relY = (y - grid.offsetY).toFloat() / grid.blockHeight - 0.5f
        val x0 = floor(relX).toInt()
        val y0 = floor(relY).toInt()
        val fx = relX - x0
        val fy = relY - y0
        if (!bicubic) {
            val c00 = blockColor(source, width, height, grid, x0, y0)
            val c10 = blockColor(source, width, height, grid, x0 + 1, y0)
            val c01 = blockColor(source, width, height, grid, x0, y0 + 1)
            val c11 = blockColor(source, width, height, grid, x0 + 1, y0 + 1)
            val top = mixColor(c00, c10, fx)
            val bottom = mixColor(c01, c11, fx)
            return mixColor(top, bottom, fy)
        }
        val rows = Array(4) { row ->
            val samples = IntArray(4) { col ->
                blockColor(source, width, height, grid, x0 - 1 + col, y0 - 1 + row)
            }
            catmull(samples[0], samples[1], samples[2], samples[3], fx)
        }
        return catmull(rows[0], rows[1], rows[2], rows[3], fy)
    }

    private fun directional(
        source: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        x: Int,
        y: Int,
        bilinear: Int,
    ): Int {
        val bx = floorDiv(x - grid.offsetX, grid.blockWidth)
        val by = floorDiv(y - grid.offsetY, grid.blockHeight)
        val left = blockColor(source, width, height, grid, bx - 1, by)
        val mid = blockColor(source, width, height, grid, bx, by)
        val right = blockColor(source, width, height, grid, bx + 1, by)
        val up = blockColor(source, width, height, grid, bx, by - 1)
        val down = blockColor(source, width, height, grid, bx, by + 1)
        val dL = colorDistance(left, mid)
        val dR = colorDistance(mid, right)
        val dU = colorDistance(up, mid)
        val dD = colorDistance(mid, down)
        val verticalEdge = isStep(dL, dR)
        val horizontalEdge = isStep(dU, dD)
        val relX = (x - grid.offsetX).toFloat() / grid.blockWidth - 0.5f
        val relY = (y - grid.offsetY).toFloat() / grid.blockHeight - 0.5f
        val x0 = floor(relX).toInt()
        val y0 = floor(relY).toInt()
        val fx = relX - x0
        val fy = relY - y0
        val alongY = mixColor(
            blockColor(source, width, height, grid, bx, y0),
            blockColor(source, width, height, grid, bx, y0 + 1),
            fy,
        )
        val alongX = mixColor(
            blockColor(source, width, height, grid, x0, by),
            blockColor(source, width, height, grid, x0 + 1, by),
            fx,
        )
        val diagonalDown = mixColor(
            blockColor(source, width, height, grid, x0, y0),
            blockColor(source, width, height, grid, x0 + 1, y0 + 1),
            ((fx + fy) * 0.5f).coerceIn(0f, 1f),
        )
        val diagonalUp = mixColor(
            blockColor(source, width, height, grid, x0 + 1, y0),
            blockColor(source, width, height, grid, x0, y0 + 1),
            ((fx + (1f - fy)) * 0.5f).coerceIn(0f, 1f),
        )
        return when {
            verticalEdge && horizontalEdge -> mid
            verticalEdge -> alongY
            horizontalEdge -> alongX
            diagonalStep(dL, dR, dU, dD) -> if (dL + dD < dR + dU) diagonalDown else diagonalUp
            else -> bilinear
        }
    }

    private fun isStep(towardNegative: Float, towardPositive: Float): Boolean {
        val peak = max(towardNegative, towardPositive)
        val other = min(towardNegative, towardPositive)
        return peak > 0.18f && peak - other > 0.12f && other < peak * 0.55f
    }

    private fun diagonalStep(dL: Float, dR: Float, dU: Float, dD: Float): Boolean {
        val horizontal = max(dL, dR)
        val vertical = max(dU, dD)
        return horizontal > 0.18f && vertical > 0.18f && abs(horizontal - vertical) < 0.12f && !isStep(dL, dR) && !isStep(dU, dD)
    }

    private fun edgeStrength(
        source: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        x: Int,
        y: Int,
    ): Float {
        val bx = floorDiv(x - grid.offsetX, grid.blockWidth)
        val by = floorDiv(y - grid.offsetY, grid.blockHeight)
        val mid = blockColor(source, width, height, grid, bx, by)
        val dL = colorDistance(blockColor(source, width, height, grid, bx - 1, by), mid)
        val dR = colorDistance(mid, blockColor(source, width, height, grid, bx + 1, by))
        val dU = colorDistance(blockColor(source, width, height, grid, bx, by - 1), mid)
        val dD = colorDistance(mid, blockColor(source, width, height, grid, bx, by + 1))
        return max(max(dL, dR), max(dU, dD))
    }

    private fun deblockIfWeak(
        color: Int,
        bilinear: Int,
        source: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        x: Int,
        y: Int,
        quality: QualityLevel,
    ): Int {
        if (quality == QualityLevel.LOW) return color
        val localX = floorMod(x - grid.offsetX, grid.blockWidth)
        val localY = floorMod(y - grid.offsetY, grid.blockHeight)
        val dist = min(min(localX, grid.blockWidth - 1 - localX), min(localY, grid.blockHeight - 1 - localY))
        if (dist > 1) return color
        val edge = edgeStrength(source, width, height, grid, x, y)
        if (edge >= 0.18f) return color
        return mixColor(color, bilinear, 0.55f)
    }

    private fun temporalBlend(
        spatial: IntArray,
        source: IntArray,
        width: Int,
        height: Int,
        previousSource: IntArray,
        previousReconstruction: IntArray,
        previousWidth: Int,
        previousHeight: Int,
        grid: MosaicGrid,
        quality: QualityLevel,
    ): IntArray {
        if (previousWidth != width || previousHeight != height || previousSource.size < width * height) return spatial
        val radius = if (quality == QualityLevel.HIGH) 2 else 1
        val blocksX = ceilDiv(width + grid.blockWidth, grid.blockWidth) + 2
        val blocksY = ceilDiv(height + grid.blockHeight, grid.blockHeight) + 2
        val motionX = IntArray(blocksX * blocksY)
        val motionY = IntArray(blocksX * blocksY)
        val accept = BooleanArray(blocksX * blocksY)
        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val blockIndexX = bx - 1
                val blockIndexY = by - 1
                val match = matchBlock(
                    source,
                    width,
                    height,
                    previousSource,
                    previousWidth,
                    previousHeight,
                    grid,
                    blockIndexX,
                    blockIndexY,
                    radius,
                )
                val index = by * blocksX + bx
                motionX[index] = match.dx
                motionY[index] = match.dy
                accept[index] = match.sad < 0.12f
            }
        }
        val dst = IntArray(spatial.size)
        for (y in 0 until height) {
            val by = floorDiv(y - grid.offsetY, grid.blockHeight) + 1
            val row = y * width
            for (x in 0 until width) {
                val bx = floorDiv(x - grid.offsetX, grid.blockWidth) + 1
                val index = (by.coerceIn(0, blocksY - 1)) * blocksX + bx.coerceIn(0, blocksX - 1)
                if (!accept[index]) {
                    dst[row + x] = spatial[row + x]
                    continue
                }
                val sampleX = (x - motionX[index] * grid.blockWidth).coerceIn(0, width - 1)
                val sampleY = (y - motionY[index] * grid.blockHeight).coerceIn(0, height - 1)
                dst[row + x] = mixColor(spatial[row + x], previousReconstruction[sampleY * width + sampleX], 0.40f)
            }
        }
        return dst
    }

    private fun neighborhoodSad(
        current: IntArray,
        width: Int,
        height: Int,
        previous: IntArray,
        grid: MosaicGrid,
        blockX: Int,
        blockY: Int,
        dx: Int,
        dy: Int,
    ): Float {
        var sad = 0f
        var count = 0
        for (ky in -1..1) {
            for (kx in -1..1) {
                val a = blockColor(current, width, height, grid, blockX + kx, blockY + ky)
                val b = blockColor(previous, width, height, grid, blockX + kx + dx, blockY + ky + dy)
                sad += colorDistance(a, b)
                count++
            }
        }
        return sad / count
    }

    private fun sharpenAdaptive(
        source: IntArray,
        width: Int,
        height: Int,
        quality: QualityLevel,
        blockW: Int,
        blockH: Int,
        grid: MosaicGrid,
    ): IntArray {
        val base = when (quality) {
            QualityLevel.LOW -> 0.22f
            QualityLevel.MEDIUM -> 0.40f
            QualityLevel.HIGH -> 0.55f
        }
        val dst = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val center = source[y * width + x]
                val north = source[(y - 1).coerceAtLeast(0) * width + x]
                val south = source[(y + 1).coerceAtMost(height - 1) * width + x]
                val west = source[y * width + (x - 1).coerceAtLeast(0)]
                val east = source[y * width + (x + 1).coerceAtMost(width - 1)]
                val blur = mixColor(mixColor(north, south, 0.5f), mixColor(west, east, 0.5f), 0.5f)
                val local = colorDistance(center, blur)
                val amount = if (quality == QualityLevel.HIGH) {
                    base * (0.35f + local * 2.2f).coerceIn(0.35f, 1.25f)
                } else {
                    base
                }
                val onSeam = floorMod(x - grid.offsetX, blockW) == 0 || floorMod(y - grid.offsetY, blockH) == 0
                val used = if (onSeam && quality != QualityLevel.LOW) amount * 0.35f else amount
                dst[y * width + x] = unsharp(center, blur, used)
            }
        }
        return dst
    }

    private fun drawGrid(source: IntArray, width: Int, height: Int, grid: MosaicGrid): IntArray {
        val dst = source.copyOf()
        val mark = argb(60, 220, 120)
        for (y in 0 until height) {
            val onRow = floorMod(y - grid.offsetY, grid.blockHeight) == 0
            val row = y * width
            for (x in 0 until width) {
                val onCol = floorMod(x - grid.offsetX, grid.blockWidth) == 0
                if (onRow || onCol) dst[row + x] = mixColor(source[row + x], mark, 0.65f)
            }
        }
        return dst
    }

    private fun blockColor(source: IntArray, width: Int, height: Int, grid: MosaicGrid, bx: Int, by: Int): Int {
        val x = grid.offsetX + bx * grid.blockWidth + grid.blockWidth / 2
        val y = grid.offsetY + by * grid.blockHeight + grid.blockHeight / 2
        return source[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    }

    private fun catmull(p0: Int, p1: Int, p2: Int, p3: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = component(p0, shift).toFloat()
            val b = component(p1, shift).toFloat()
            val c = component(p2, shift).toFloat()
            val d = component(p3, shift).toFloat()
            val c0 = b
            val c1 = 0.5f * (c - a)
            val c2 = a - 2.5f * b + 2f * c - 0.5f * d
            val c3 = -0.5f * a + 1.5f * b - 1.5f * c + 0.5f * d
            val value = ((c3 * t + c2) * t + c1) * t + c0
            return value.roundToInt().coerceIn(0, 255)
        }
        return argb(channel(16), channel(8), channel(0))
    }

    private fun unsharp(center: Int, blur: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val c = component(center, shift)
            val b = component(blur, shift)
            return (c + amount * (c - b)).roundToInt().coerceIn(0, 255)
        }
        return argb(channel(16), channel(8), channel(0))
    }

    private fun mixColor(a: Int, b: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val av = component(a, shift)
            val bv = component(b, shift)
            return (av + (bv - av) * clamped).roundToInt().coerceIn(0, 255)
        }
        return argb(channel(16), channel(8), channel(0))
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = component(a, 16) - component(b, 16)
        val dg = component(a, 8) - component(b, 8)
        val db = component(a, 0) - component(b, 0)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441.67f
    }

    private fun component(color: Int, shift: Int): Int = (color shr shift) and 0xFF

    private fun lumaOf(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 54 + g * 183 + b * 19) shr 8
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun floorDiv(value: Int, size: Int): Int = floor(value.toFloat() / size).toInt()

    private fun floorMod(value: Int, size: Int): Int {
        val mod = value % size
        return if (mod < 0) mod + size else mod
    }

    private fun ceilDiv(value: Int, size: Int): Int = (value + size - 1) / size

    data class MotionMatch(val dx: Int, val dy: Int, val sad: Float)
}
