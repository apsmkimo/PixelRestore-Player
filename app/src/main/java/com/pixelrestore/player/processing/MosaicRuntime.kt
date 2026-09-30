package com.pixelrestore.player.processing

import com.pixelrestore.player.settings.MosaicBlockSize
import com.pixelrestore.player.settings.QualityLevel
import com.pixelrestore.player.settings.UserSettings

/**
 * Live mosaic lattice and CPU-side history. The GL thread and the CPU fallback
 * both write [grid]; the UI reads it.
 */
class MosaicRuntime {
    @Volatile var manualBlock: Int? = null
    @Volatile var quality: QualityLevel = QualityLevel.MEDIUM
    @Volatile var debugView: MosaicDebugView = MosaicDebugView.FINAL
    @Volatile var compareOriginal: Boolean = false
    @Volatile var grid: MosaicGrid = MosaicGrid.UNDETECTED
        private set
    @Volatile var lastDetectMs: Float = 0f
        private set
    @Volatile var roi: RoiBox? = null
        private set

    var onGrid: ((MosaicGrid) -> Unit)? = null
    var tileEnhancer: TileEnhancer? = null

    // SMCPKG_SUPPORT>>>Cursor037
    private val roiTracker = RoiTracker()
    private val sesrInput = FloatArray(SesrTiles.inputFloats())
    private var holdFrames = 0
    // SMCPKG_SUPPORT<<<Cursor038

    private var cpuPreviousSource: IntArray? = null
    private var cpuPreviousReconstruction: IntArray? = null
    private var cpuWidth = 0
    private var cpuHeight = 0

    fun configure(settings: UserSettings) {
        val nextManual = settings.mosaicBlockSize.pixels
        if (nextManual != manualBlock) {
            holdFrames = 0
            roiTracker.clear()
        }
        manualBlock = nextManual
        quality = settings.mosaicQuality
        debugView = if (settings.mosaicDebug) settings.mosaicDebugView else MosaicDebugView.FINAL
    }

    fun showsFinal(): Boolean = !compareOriginal && debugView == MosaicDebugView.FINAL

    fun shaderDebugView(): Float {
        if (compareOriginal) return MosaicDebugView.ORIGINAL.shaderValue
        return debugView.shaderValue
    }

    /**
     * [argb] is a 1:1 crop of the source frame whose top-left is [cropX], [cropY].
     */
    fun observeCrop(argb: IntArray, cropWidth: Int, cropHeight: Int, cropX: Int, cropY: Int) {
        val start = System.nanoTime()
        val found = MosaicDetector.detect(argb, cropWidth, cropHeight, manualBlock)
        lastDetectMs = (System.nanoTime() - start) / 1_000_000f
        // SMCPKG_SUPPORT>>>Cursor039
        // val next = if (!found.usable) {
        //     MosaicGrid.UNDETECTED.copy(confidence = found.confidence)
        // } else {
        //     val size = found.blockWidth.coerceAtLeast(2)
        //     found.copy(
        //         offsetX = floorMod(cropX + found.offsetX, size),
        //         offsetY = floorMod(cropY + found.offsetY, size),
        //     )
        // }
        val next = if (!found.usable) {
            val previous = grid
            if (previous.usable && holdFrames < MAX_HOLD_FRAMES) {
                holdFrames += 1
                previous.copy(
                    confidence = found.confidence,
                    held = true,
                    note = "Auto missed this frame. Keeping ${previous.blockWidth}×${previous.blockHeight}.",
                )
            } else {
                holdFrames = 0
                MosaicGrid.UNDETECTED.copy(
                    confidence = found.confidence,
                    note = "Auto did not find a mosaic. Pick a block size.",
                )
            }
        } else {
            holdFrames = 0
            val size = found.blockWidth.coerceAtLeast(2)
            found.copy(
                offsetX = floorMod(cropX + found.offsetX, size),
                offsetY = floorMod(cropY + found.offsetY, size),
                held = false,
                note = "",
            )
        }
        // SMCPKG_SUPPORT<<<Cursor040
        grid = next
        onGrid?.invoke(next)
    }

    fun processCpu(pixels: IntArray, width: Int, height: Int): IntArray {
        val cropW = width.coerceAtMost(192)
        val cropH = height.coerceAtMost(108)
        val cropX = ((width - cropW) / 2).coerceAtLeast(0)
        val cropY = ((height - cropH) / 2).coerceAtLeast(0)
        val crop = IntArray(cropW * cropH)
        for (y in 0 until cropH) {
            val src = (cropY + y) * width + cropX
            pixels.copyInto(crop, y * cropW, src, src + cropW)
        }
        observeCrop(crop, cropW, cropH, cropX, cropY)
        val result = MosaicReconstruction.process(
            source = pixels,
            width = width,
            height = height,
            grid = grid,
            quality = quality,
            debugView = debugView,
            compareOriginal = compareOriginal,
            previousSource = cpuPreviousSource,
            previousReconstruction = cpuPreviousReconstruction,
            previousWidth = cpuWidth,
            previousHeight = cpuHeight,
        )
        if (showsFinal() && grid.usable) {
            cpuPreviousSource = pixels.copyOf()
            cpuPreviousReconstruction = result.pixels
            cpuWidth = width
            cpuHeight = height
        }
        return result.pixels
    }

    /**
     * Classical detect + ROI track, then one SESR-M5 tile blended onto the ROI.
     * Without a loaded model the frame is unchanged and [MosaicGrid.note] says so.
     */
    fun processMl(pixels: IntArray, width: Int, height: Int): IntArray {
        if (compareOriginal) {
            observeCenter(pixels, width, height)
            return pixels.copyOf()
        }
        observeCenter(pixels, width, height)
        val region = if (grid.usable) {
            RoiExtractor.mosaicRegion(pixels, width, height, grid) ?: RoiBox(0, 0, width, height)
        } else {
            RoiBox(0, 0, width, height)
        }
        val tracked = roiTracker.update(region)
        roi = tracked
        val box = tracked ?: return pixels.copyOf()
        val enhancer = tileEnhancer
        if (enhancer == null) {
            publishNote("SESR-M5 is not loaded. Showing the original frame.")
            return pixels.copyOf()
        }
        SesrTiles.packRgbNchw(pixels, width, height, box, sesrInput)
        val enhanced = enhancer.enhance(sesrInput)
        if (enhanced == null) {
            publishNote("SESR-M5 did not run. Showing the original frame.")
            return pixels.copyOf()
        }
        publishNote("SESR-M5 2× estimate on ${box.width}×${box.height}. Not the original pixels.")
        return SesrTiles.blend(pixels, width, height, box, enhanced)
    }

    private fun observeCenter(pixels: IntArray, width: Int, height: Int) {
        val cropW = width.coerceAtMost(192)
        val cropH = height.coerceAtMost(108)
        val cropX = ((width - cropW) / 2).coerceAtLeast(0)
        val cropY = ((height - cropH) / 2).coerceAtLeast(0)
        val crop = IntArray(cropW * cropH)
        for (y in 0 until cropH) {
            val src = (cropY + y) * width + cropX
            pixels.copyInto(crop, y * cropW, src, src + cropW)
        }
        observeCrop(crop, cropW, cropH, cropX, cropY)
    }

    private fun publishNote(text: String) {
        val next = grid.copy(note = text)
        grid = next
        onGrid?.invoke(next)
    }

    private fun floorMod(value: Int, size: Int): Int {
        val mod = value % size
        return if (mod < 0) mod + size else mod
    }

    private companion object {
        const val MAX_HOLD_FRAMES = 8
    }
}

val MosaicBlockSize.pixels: Int?
    get() = when (this) {
        MosaicBlockSize.AUTO -> null
        MosaicBlockSize.B2 -> 2
        MosaicBlockSize.B4 -> 4
        MosaicBlockSize.B8 -> 8
        MosaicBlockSize.B16 -> 16
        MosaicBlockSize.B32 -> 32
    }
