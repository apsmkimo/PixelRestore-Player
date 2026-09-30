package com.pixelrestore.player

import com.pixelrestore.player.processing.MosaicDetector
import com.pixelrestore.player.processing.MosaicReconstruction
import com.pixelrestore.player.processing.MosaicRuntime
import com.pixelrestore.player.processing.RoiBox
import com.pixelrestore.player.processing.RoiExtractor
import com.pixelrestore.player.processing.RoiTracker
import com.pixelrestore.player.processing.SesrTiles
import com.pixelrestore.player.processing.TileEnhancer
import com.pixelrestore.player.processing.roiIou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MlEnhanceTest {
    @Test
    fun bundledSesrAssetIsTheSmallInt8Graph() {
        val model = File("src/main/assets/models/sesr_m5_int8.onnx")
        assertTrue(model.isFile)
        assertTrue(model.length() in 80_000..200_000)
        val bytes = model.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("Model::input_0"))
        assertTrue(File("src/main/assets/models/NOTICE").isFile)
        assertTrue(File("src/main/assets/models/LICENSE-SESR-Apache-2.0.txt").isFile)
        assertFalse(File("src/main/assets/models/realesr-general-x4v3.onnx").exists())
    }

    @Test
    fun roiBoxFollowsFlatMosaicCellsAndTracksOverlap() {
        val width = 96
        val height = 64
        val frame = IntArray(width * height) { index ->
            val x = index % width
            argb(x * 2, x * 2, x * 2)
        }
        val block = 8
        for (y in 16 until 48) {
            for (x in 24 until 72) {
                val cellX = 24 + ((x - 24) / block) * block
                val cellY = 16 + ((y - 16) / block) * block
                frame[y * width + x] = frame[cellY * width + cellX]
            }
        }
        val grid = MosaicDetector.detect(frame, width, height, manualBlock = block)
        assertTrue(grid.usable)
        val roi = RoiExtractor.mosaicRegion(frame, width, height, grid)
        assertNotNull(roi)
        roi!!
        assertTrue(roi.left < 24)
        assertTrue(roi.top < 16)
        assertTrue(roi.right > 72)
        assertTrue(roi.bottom > 48)
        assertTrue(roi.left > 0)
        assertTrue(roi.right < width)

        val tracker = RoiTracker()
        val first = tracker.update(roi)
        val shifted = roi.copy(left = roi.left + 2, right = roi.right + 2)
        val second = tracker.update(shifted)
        assertNotNull(second)
        assertTrue(roiIou(first!!, shifted) > 0.15f)
        assertTrue(kotlin.math.abs(second!!.left - roi.left) < (shifted.left - roi.left))
        assertEquals(second, tracker.update(null))
    }

    @Test
    fun sesrBlendWritesOnlyTheRoiWhenTheEnhancerReturnsATile() {
        val width = 64
        val height = 48
        val frame = IntArray(width * height) { index ->
            val x = index % width
            argb((x * 12).coerceAtMost(255), 8, 8)
        }
        val block = 8
        for (y in 16 until 40) {
            for (x in 16 until 48) {
                val cellX = 16 + ((x - 16) / block) * block
                val cellY = 16 + ((y - 16) / block) * block
                frame[y * width + x] = frame[cellY * width + cellX]
            }
        }
        val runtime = MosaicRuntime()
        runtime.manualBlock = block
        runtime.tileEnhancer = TileEnhancer {
            FloatArray(SesrTiles.outputFloats()) { 250f }
        }
        val output = runtime.processMl(frame, width, height)
        assertFalse(output.contentEquals(frame))
        assertEquals(frame[0], output[0])
        val inside = output[24 * width + 24]
        assertTrue(((inside shr 16) and 0xFF) > 200)
        assertTrue(runtime.grid.note.contains("SESR-M5"))
        val unchanged = MosaicRuntime().processMl(frame, width, height)
        assertTrue(unchanged.contentEquals(frame))
        assertTrue(runtime.roi != null)
    }

    @Test
    fun autoMissKeepsTheLastBlockThenSaysToPickOne() {
        val width = 64
        val height = 64
        val mosaic = MosaicReconstruction.pixelateAverage(scene(width, height), width, height, 8, 0, 0)
        val runtime = MosaicRuntime()
        runtime.observeCrop(mosaic, width, height, 0, 0)
        assertTrue(runtime.grid.usable)
        assertFalse(runtime.grid.held)
        val smooth = IntArray(width * height) { index ->
            val x = index % width
            argb(x * 3, x * 3, x * 3)
        }
        runtime.observeCrop(smooth, width, height, 0, 0)
        assertTrue(runtime.grid.held)
        assertEquals(8, runtime.grid.blockWidth)
        assertTrue(runtime.grid.note.contains("Keeping"))
        repeat(8) {
            runtime.observeCrop(smooth, width, height, 0, 0)
        }
        assertFalse(runtime.grid.usable)
        assertTrue(runtime.grid.note.contains("Pick a block size"))
    }

    @Test
    fun packUsesRgb255AndHasTheFixedSesrShape() {
        val width = 8
        val height = 8
        val red = IntArray(width * height) { argb(255, 0, 0) }
        val dest = FloatArray(SesrTiles.inputFloats())
        SesrTiles.packRgbNchw(red, width, height, RoiBox(0, 0, width, height), dest)
        assertEquals(SesrTiles.INPUT * SesrTiles.INPUT * 3, dest.size)
        assertEquals(255f, dest[0], 0.1f)
        assertEquals(0f, dest[SesrTiles.INPUT * SesrTiles.INPUT], 0.1f)
        val blended = SesrTiles.blend(
            source = IntArray(width * height) { argb(1, 2, 3) },
            width = width,
            height = height,
            roi = RoiBox(0, 0, 2, 2),
            nchw = FloatArray(SesrTiles.outputFloats()) { 9f },
        )
        assertEquals(1, (blended[3 * width] shr 16) and 0xFF)
        assertEquals(9, (blended[0] shr 16) and 0xFF)
    }

    private fun scene(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val edge = if (x < width / 2) 20 else 220
        val diagonal = ((x + y) * 255) / (width + height)
        argb(edge, diagonal, 40)
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
