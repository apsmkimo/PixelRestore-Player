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

    var onGrid: ((MosaicGrid) -> Unit)? = null

    private var cpuPreviousSource: IntArray? = null
    private var cpuPreviousReconstruction: IntArray? = null
    private var cpuWidth = 0
    private var cpuHeight = 0

    fun configure(settings: UserSettings) {
        manualBlock = settings.mosaicBlockSize.pixels
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
        val next = if (!found.usable) {
            MosaicGrid.UNDETECTED.copy(confidence = found.confidence)
        } else {
            val size = found.blockWidth.coerceAtLeast(2)
            found.copy(
                offsetX = floorMod(cropX + found.offsetX, size),
                offsetY = floorMod(cropY + found.offsetY, size),
            )
        }
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

    private fun floorMod(value: Int, size: Int): Int {
        val mod = value % size
        return if (mod < 0) mod + size else mod
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
