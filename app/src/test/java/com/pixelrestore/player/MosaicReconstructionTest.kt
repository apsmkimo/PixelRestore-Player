package com.pixelrestore.player

import com.pixelrestore.player.processing.MosaicDebugView
import com.pixelrestore.player.processing.MosaicDetector
import com.pixelrestore.player.processing.MosaicGrid
import com.pixelrestore.player.processing.MosaicReconstruction
import com.pixelrestore.player.processing.CpuPixelFilters
import com.pixelrestore.player.settings.QualityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicReconstructionTest {
    @Test
    fun detectorFindsSquareLatticesAndPhase() {
        val width = 96
        val height = 96
        val scene = scene(width, height)
        for (block in intArrayOf(4, 8, 16)) {
            val mosaic = MosaicReconstruction.pixelateAverage(scene, width, height, block, 0, 0)
            val grid = MosaicDetector.detect(mosaic, width, height, manualBlock = null)
            assertTrue("block $block detected", grid.detected)
            assertEquals(block, grid.blockWidth)
            assertEquals(block, grid.blockHeight)
            assertEquals(0, grid.offsetX)
            assertEquals(0, grid.offsetY)
        }
        val shifted = MosaicReconstruction.pixelateAverage(scene, width, height, 8, 3, 5)
        val phase = MosaicDetector.detect(shifted, width, height, manualBlock = null)
        assertTrue(phase.detected)
        assertEquals(8, phase.blockWidth)
        assertEquals(3, phase.offsetX)
        assertEquals(5, phase.offsetY)
    }

    @Test
    fun manualBlockSizeIsUsedWhenAutoWouldMiss() {
        val width = 64
        val height = 64
        val smooth = IntArray(width * height) { index ->
            val x = index % width
            argb(x * 3, x * 3, x * 3)
        }
        val missed = MosaicDetector.detect(smooth, width, height, manualBlock = null)
        assertFalse(missed.detected)
        val manual = MosaicDetector.detect(smooth, width, height, manualBlock = 16)
        assertTrue(manual.manual)
        assertEquals(16, manual.blockWidth)
        assertTrue(manual.usable)
    }

    @Test
    fun reconstructionBeatsMosaicAndBlurSharpenOnSyntheticMetrics() {
        val width = 96
        val height = 96
        val original = ramp(width, height)
        val block = 8
        val mosaic = MosaicReconstruction.pixelateAverage(original, width, height, block, 0, 0)
        val grid = MosaicGrid(block, block, 0, 0, confidence = 4f, detected = true, manual = false)
        val restored = MosaicReconstruction.process(
            source = mosaic,
            width = width,
            height = height,
            grid = grid,
            quality = QualityLevel.MEDIUM,
            debugView = MosaicDebugView.FINAL,
            compareOriginal = false,
            previousSource = null,
            previousReconstruction = null,
            previousWidth = 0,
            previousHeight = 0,
        ).pixels
        val blurred = CpuPixelFilters.boxMix(mosaic, width, height, 1f)
        val baseline = CpuPixelFilters.sharpen(blurred, width, height, 0.8f)

        val mosaicBoundary = MosaicReconstruction.boundaryStrength(mosaic, width, height, block, 0, 0)
        val restoredBoundary = MosaicReconstruction.boundaryStrength(restored, width, height, block, 0, 0)
        val baselineBoundary = MosaicReconstruction.boundaryStrength(baseline, width, height, block, 0, 0)
        val mosaicPsnr = MosaicReconstruction.psnr(original, mosaic)
        val restoredPsnr = MosaicReconstruction.psnr(original, restored)
        val baselinePsnr = MosaicReconstruction.psnr(original, baseline)
        val mosaicSsim = MosaicReconstruction.ssim(original, mosaic)
        val restoredSsim = MosaicReconstruction.ssim(original, restored)
        val baselineSsim = MosaicReconstruction.ssim(original, baseline)

        assertTrue(restoredBoundary < mosaicBoundary * 0.55)
        assertTrue(restoredBoundary < baselineBoundary)
        assertTrue(restoredPsnr > mosaicPsnr + 0.5)
        assertTrue(restoredPsnr > baselinePsnr)
        assertTrue(restoredSsim > mosaicSsim)
        assertTrue(restoredSsim > baselineSsim)
    }

    @Test
    fun directionalReconstructionKeepsAlignedEdgesSharperThanBilinear() {
        val width = 64
        val height = 32
        val original = IntArray(width * height) { index ->
            val x = index % width
            if (x < 32) argb(0, 0, 0) else argb(255, 255, 255)
        }
        val mosaic = MosaicReconstruction.pixelateAverage(original, width, height, 8, 0, 0)
        val grid = MosaicGrid(8, 8, 0, 0, 4f, detected = true, manual = false)
        val low = finish(mosaic, width, height, grid, QualityLevel.LOW)
        val medium = finish(mosaic, width, height, grid, QualityLevel.MEDIUM)
        val lowWidth = MosaicReconstruction.transitionWidth(low, width, row = 16, dark = 30, bright = 225)
        val mediumWidth = MosaicReconstruction.transitionWidth(medium, width, row = 16, dark = 30, bright = 225)
        assertTrue(mediumWidth < lowWidth)
        assertTrue(mediumWidth <= 2)
    }

    @Test
    fun blockMatchFollowsAOneBlockShift() {
        val width = 64
        val height = 48
        val grid = MosaicGrid(8, 8, 0, 0, 4f, true, false)
        val current = MosaicReconstruction.pixelateAverage(scene(width, height), width, height, 8, 0, 0)
        val earlierScene = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            scene(width, height)[(y * width + (x + 8).coerceAtMost(width - 1))]
        }
        val previous = MosaicReconstruction.pixelateAverage(earlierScene, width, height, 8, 0, 0)
        val match = MosaicReconstruction.matchBlock(
            current = current,
            width = width,
            height = height,
            previous = previous,
            previousWidth = width,
            previousHeight = height,
            grid = grid,
            blockX = 3,
            blockY = 2,
            radius = 2,
        )
        assertEquals(-1, match.dx)
        assertEquals(0, match.dy)
        assertTrue(match.sad < 0.12f)
    }

    @Test
    fun temporalBlendUsesTheMatchedPreviousReconstruction() {
        val width = 64
        val height = 48
        val grid = MosaicGrid(8, 8, 0, 0, 4f, true, false)
        val original = scene(width, height)
        val mosaic = MosaicReconstruction.pixelateAverage(original, width, height, 8, 0, 0)
        val spatialOnly = finish(mosaic, width, height, grid, QualityLevel.MEDIUM)
        val betterPrevious = original
        val temporal = MosaicReconstruction.process(
            source = mosaic,
            width = width,
            height = height,
            grid = grid,
            quality = QualityLevel.MEDIUM,
            debugView = MosaicDebugView.FINAL,
            compareOriginal = false,
            previousSource = mosaic,
            previousReconstruction = betterPrevious,
            previousWidth = width,
            previousHeight = height,
        ).pixels
        val spatialPsnr = MosaicReconstruction.psnr(original, spatialOnly)
        val temporalPsnr = MosaicReconstruction.psnr(original, temporal)
        assertTrue(temporalPsnr > spatialPsnr + 0.4)
    }

    @Test
    fun debugViewsAndCompareAreDistinct() {
        val width = 32
        val height = 32
        val mosaic = MosaicReconstruction.pixelateAverage(scene(width, height), width, height, 8, 0, 0)
        val grid = MosaicGrid(8, 8, 0, 0, 3f, true, false)
        val original = MosaicReconstruction.process(
            mosaic, width, height, grid, QualityLevel.LOW, MosaicDebugView.ORIGINAL, false, null, null, 0, 0,
        ).pixels
        val gridView = MosaicReconstruction.process(
            mosaic, width, height, grid, QualityLevel.LOW, MosaicDebugView.GRID, false, null, null, 0, 0,
        ).pixels
        val reconstructed = MosaicReconstruction.process(
            mosaic, width, height, grid, QualityLevel.LOW, MosaicDebugView.RECONSTRUCTED, false, null, null, 0, 0,
        ).pixels
        val compared = MosaicReconstruction.process(
            mosaic, width, height, grid, QualityLevel.HIGH, MosaicDebugView.FINAL, true, null, null, 0, 0,
        ).pixels
        assertEquals(mosaic[0], original[0])
        assertEquals(mosaic[0], compared[0])
        var gridMarks = 0
        for (i in gridView.indices) if (gridView[i] != mosaic[i]) gridMarks++
        assertTrue(gridMarks > 10)
        assertFalse(reconstructed.contentEquals(mosaic))
    }

    private fun finish(
        mosaic: IntArray,
        width: Int,
        height: Int,
        grid: MosaicGrid,
        quality: QualityLevel,
    ): IntArray = MosaicReconstruction.process(
        source = mosaic,
        width = width,
        height = height,
        grid = grid,
        quality = quality,
        debugView = MosaicDebugView.FINAL,
        compareOriginal = false,
        previousSource = null,
        previousReconstruction = null,
        previousWidth = 0,
        previousHeight = 0,
    ).pixels

    private fun ramp(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val v = (x * 160 / width + y * 70 / height).coerceIn(0, 255)
        val soft = (kotlin.math.sin(x / 9.0) * 28 + 128).toInt().coerceIn(0, 255)
        argb(v, soft, (255 - v / 2).coerceIn(0, 255))
    }

    private fun scene(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val edge = if (x < width / 2) 20 else 220
        val diagonal = ((x + y) * 255) / (width + height)
        val square = if (x in 6..18 && y in 6..18) 250 else 0
        val r = (edge * 3 + diagonal + square) / 5
        val g = (edge + diagonal * 2) / 3
        val b = if (square > 0) 250 else edge / 2
        argb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
