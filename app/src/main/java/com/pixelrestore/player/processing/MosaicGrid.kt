package com.pixelrestore.player.processing

/**
 * Estimated pixelation lattice in source pixels.
 * [detected] is false when auto-search does not find a regular grid.
 * A manual block size still fills [blockWidth] and [blockHeight] so reconstruction can run.
 */
data class MosaicGrid(
    val blockWidth: Int,
    val blockHeight: Int,
    val offsetX: Int,
    val offsetY: Int,
    val confidence: Float,
    val detected: Boolean,
    val manual: Boolean,
) {
    val usable: Boolean get() = blockWidth >= 2 && blockHeight >= 2 && (detected || manual)

    companion object {
        val UNDETECTED = MosaicGrid(
            blockWidth = 0,
            blockHeight = 0,
            offsetX = 0,
            offsetY = 0,
            confidence = 0f,
            detected = false,
            manual = false,
        )
    }
}

enum class MosaicDebugView(val label: String, val shaderValue: Float) {
    FINAL("Final Output", 0f),
    ORIGINAL("Original", 1f),
    GRID("Detected Mosaic Grid", 2f),
    RECONSTRUCTED("Reconstructed", 3f),
}
