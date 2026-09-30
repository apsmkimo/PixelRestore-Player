package com.pixelrestore.player.gpu

import com.pixelrestore.player.processing.FilterPass
import com.pixelrestore.player.processing.FilterType
import kotlin.math.abs

internal data class GpuPass(
    val kind: ShaderKind,
    val outputWidth: Int,
    val outputHeight: Int,
    val strength: Float,
    val secondary: Float,
    val toScreen: Boolean,
)

internal object GpuPassPlanner {
    fun plan(
        filters: List<FilterPass>,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): List<GpuPass> {
        val srcW = sourceWidth.coerceAtLeast(2)
        val srcH = sourceHeight.coerceAtLeast(2)
        val dstW = if (outputWidth > 1) outputWidth else srcW
        val dstH = if (outputHeight > 1) outputHeight else srcH
        val planned = filters.toMutableList()
        val sizesDiffer = abs(dstW - srcW) > 1 || abs(dstH - srcH) > 1
        if (sizesDiffer && planned.none { it.type == FilterType.SCALE_BILINEAR || it.type == FilterType.SCALE_BICUBIC }) {
            val insertAt = planned.indexOfLast { it.type == FilterType.DEBLOCK || it.type == FilterType.EDGE_SMOOTH || it.type == FilterType.DENOISE }
            val scale = FilterPass(FilterType.SCALE_BILINEAR, 1f)
            if (insertAt >= 0 && insertAt < planned.lastIndex) {
                planned.add(insertAt + 1, scale)
            } else {
                planned.add(0, scale)
            }
        }
        if (planned.isEmpty()) planned += FilterPass(FilterType.BLIT, 1f)
        var currentW = srcW
        var currentH = srcH
        val passes = ArrayList<GpuPass>(planned.size)
        planned.forEachIndexed { index, filter ->
            // SMCPKG_SUPPORT>>>Cursor019
            // val scaling = filter.type == FilterType.SCALE_BILINEAR || filter.type == FilterType.SCALE_BICUBIC
            val scaling = filter.type == FilterType.SCALE_BILINEAR ||
                filter.type == FilterType.SCALE_BICUBIC ||
                filter.type == FilterType.MOSAIC_RECONSTRUCT
            // SMCPKG_SUPPORT<<<Cursor020
            val outW = if (scaling) dstW else currentW
            val outH = if (scaling) dstH else currentH
            passes += GpuPass(
                kind = ShaderSources.kindFor(filter.type),
                outputWidth = outW,
                outputHeight = outH,
                strength = filter.strength,
                secondary = filter.secondary,
                toScreen = index == planned.lastIndex,
            )
            currentW = outW
            currentH = outH
        }
        return passes
    }
}
