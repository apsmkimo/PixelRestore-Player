package com.pixelrestore.player.processing

/**
 * One real filter stage. [strength] and [secondary] are shader/CPU uniforms.
 * SCALE_BILINEAR samples with hardware bilinear filtering into a new size.
 * SCALE_BICUBIC runs a Catmull-Rom kernel. Lanczos is not implemented.
 */
enum class FilterType {
    DEBLOCK,
    EDGE_SMOOTH,
    SCALE_BILINEAR,
    SCALE_BICUBIC,
    SHARPEN,
    DENOISE,
    CONTRAST,
    BLIT,
    /** Lattice reconstruction. [FilterPass.strength] is 0 low, 1 medium, 2 high. */
    MOSAIC_RECONSTRUCT,
    /** Block-match blend with the previous reconstruction, then sharpen. */
    MOSAIC_TEMPORAL,
    // SMCPKG_SUPPORT>>>Cursor035
    /** CPU SESR-M5 tile. The GPU path blits; the pixels are enhanced on the CPU. */
    ML_ENHANCE,
    // SMCPKG_SUPPORT<<<Cursor036
}

data class FilterPass(
    val type: FilterType,
    val strength: Float,
    val secondary: Float = 0f,
)
