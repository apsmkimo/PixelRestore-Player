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
}

data class FilterPass(
    val type: FilterType,
    val strength: Float,
    val secondary: Float = 0f,
)
