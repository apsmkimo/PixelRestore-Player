package com.pixelrestore.player.processing

import com.pixelrestore.player.device.DeviceTier
import com.pixelrestore.player.settings.EnhancementLevel
import com.pixelrestore.player.settings.FrameRateOption
import com.pixelrestore.player.settings.MosaicResolution
import com.pixelrestore.player.settings.OutputResolution
import com.pixelrestore.player.settings.QualityLevel
import com.pixelrestore.player.settings.Strength
import com.pixelrestore.player.settings.UserSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class AutoAdaptation(
    val maxHeight: Int? = null,
    val maxFps: Int? = null,
)

data class ResolvedProfile(
    val mode: ProcessingMode,
    val outputWidth: Int,
    val outputHeight: Int,
    val targetFps: Int,
    val budgetMs: Float,
    val passes: List<FilterPass>,
    val autoAdapted: Boolean,
    val sourceFps: Float,
)

object ProcessingProfileResolver {
    fun supportedHeights(maxDimension: Int?): List<Int> {
        if (maxDimension == null || maxDimension <= 0) return listOf(480, 720, 1080)
        return listOf(480, 720, 1080).filter { sixteenByNineWidth(it) <= maxDimension }
    }

    fun resolve(
        settings: UserSettings,
        tier: DeviceTier,
        videoWidth: Int,
        videoHeight: Int,
        sourceFps: Float,
        maxDimension: Int?,
        adaptation: AutoAdaptation,
    ): ResolvedProfile {
        val mode = settings.mode
        val allowed = supportedHeights(maxDimension)
        val targetFps = resolveFps(settings, tier, adaptation)
        val targetHeight = resolveHeight(settings, tier, videoHeight, allowed, adaptation)
        val (outW, outH) = if (videoWidth > 0 && videoHeight > 0 && targetHeight > 0) {
            fitEven(videoWidth, videoHeight, targetHeight, maxDimension ?: Int.MAX_VALUE)
        } else {
            0 to targetHeight
        }
        val upscaling = videoHeight > 0 && outH > videoHeight + 1
        val scaling = videoHeight > 0 && kotlin.math.abs(outH - videoHeight) > 1
        val passes = when (mode) {
            ProcessingMode.OFF -> listOf(FilterPass(FilterType.BLIT, 1f))
            ProcessingMode.MOSAIC_RESTORATION -> mosaicPasses(settings.mosaicQuality, scaling, upscaling)
            ProcessingMode.VIDEO_ENHANCEMENT -> enhancementPasses(
                settings.enhancementLevel,
                settings.noiseReduction,
                settings.sharpening,
                scaling,
                upscaling,
            )
        }
        val autoAdapted = adaptation.maxHeight != null || adaptation.maxFps != null
        return ResolvedProfile(
            mode = mode,
            outputWidth = outW,
            outputHeight = outH,
            targetFps = targetFps,
            budgetMs = 1000f / targetFps.coerceAtLeast(1),
            passes = passes,
            autoAdapted = autoAdapted && mode == ProcessingMode.MOSAIC_RESTORATION,
            sourceFps = sourceFps,
        )
    }

    fun stepDown(
        settings: UserSettings,
        currentHeight: Int,
        currentFps: Int,
        existing: AutoAdaptation,
    ): AutoAdaptation? {
        if (!settings.autoOptimization) return null
        if (settings.mode != ProcessingMode.MOSAIC_RESTORATION) return null
        if (settings.mosaicResolution == MosaicResolution.AUTO && currentHeight > 480) {
            val next = when {
                currentHeight > 1080 -> 1080
                currentHeight > 720 -> 720
                else -> 480
            }
            if (existing.maxHeight == next) return null
            return existing.copy(maxHeight = next)
        }
        if (settings.mosaicFrameRate == FrameRateOption.AUTO && currentFps > 24) {
            val next = if (currentFps > 30) 30 else 24
            if (existing.maxFps == next) return null
            return existing.copy(maxFps = next)
        }
        return null
    }

    fun fitEven(srcW: Int, srcH: Int, targetHeight: Int, maxEdge: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0 || targetHeight <= 0) return 0 to 0
        val aspect = srcW.toFloat() / srcH.toFloat()
        var height = targetHeight.coerceAtLeast(2)
        var width = (height * aspect).roundToInt().coerceAtLeast(2)
        val edge = max(width, height)
        if (edge > maxEdge && maxEdge > 0) {
            val scale = maxEdge.toFloat() / edge.toFloat()
            width = (width * scale).roundToInt().coerceAtLeast(2)
            height = (height * scale).roundToInt().coerceAtLeast(2)
        }
        if (width % 2 != 0) width -= 1
        if (height % 2 != 0) height -= 1
        return width.coerceAtLeast(2) to height.coerceAtLeast(2)
    }

    private fun resolveHeight(
        settings: UserSettings,
        tier: DeviceTier,
        videoHeight: Int,
        allowed: List<Int>,
        adaptation: AutoAdaptation,
    ): Int {
        val requested = when (settings.mode) {
            ProcessingMode.OFF -> if (videoHeight > 0) videoHeight else 0
            ProcessingMode.MOSAIC_RESTORATION -> when (settings.mosaicResolution) {
                MosaicResolution.AUTO -> {
                    val base = if (settings.autoOptimization) {
                        tier.recommendedHeight
                    } else {
                        if (videoHeight > 0) videoHeight else tier.recommendedHeight
                    }
                    val capped = if (videoHeight > 0) min(base, videoHeight) else base
                    val adapted = adaptation.maxHeight?.let { min(capped, it) } ?: capped
                    adapted
                }
                MosaicResolution.P480 -> 480
                MosaicResolution.P720 -> 720
                MosaicResolution.P1080 -> 1080
            }
            ProcessingMode.VIDEO_ENHANCEMENT -> when (settings.enhancementResolution) {
                OutputResolution.ORIGINAL -> if (videoHeight > 0) videoHeight else 0
                OutputResolution.P720 -> 720
                OutputResolution.P1080 -> 1080
            }
        }
        if (requested <= 0) return 0
        if (settings.mode == ProcessingMode.MOSAIC_RESTORATION &&
            settings.mosaicResolution == MosaicResolution.AUTO
        ) {
            return floorAllowed(requested, allowed)
        }
        if (settings.mode == ProcessingMode.VIDEO_ENHANCEMENT &&
            settings.enhancementResolution == OutputResolution.ORIGINAL
        ) {
            return requested
        }
        return clampManual(requested, allowed)
    }

    private fun resolveFps(
        settings: UserSettings,
        tier: DeviceTier,
        adaptation: AutoAdaptation,
    ): Int {
        val raw = if (settings.mode == ProcessingMode.MOSAIC_RESTORATION) {
            when (settings.mosaicFrameRate) {
                FrameRateOption.AUTO -> {
                    val base = if (settings.autoOptimization) tier.recommendedFps else 30
                    adaptation.maxFps?.let { min(base, it) } ?: base
                }
                FrameRateOption.FPS_24 -> 24
                FrameRateOption.FPS_30 -> 30
                FrameRateOption.FPS_60 -> 60
            }
        } else {
            30
        }
        return raw.coerceIn(24, 60)
    }

    private fun floorAllowed(height: Int, allowed: List<Int>): Int {
        if (allowed.isEmpty()) return height
        return allowed.filter { it <= height + 8 }.maxOrNull() ?: height
    }

    private fun clampManual(height: Int, allowed: List<Int>): Int {
        if (allowed.isEmpty()) return height
        if (height in allowed) return height
        return allowed.filter { it <= height }.maxOrNull() ?: allowed.min()
    }

    private fun sixteenByNineWidth(height: Int): Int = when (height) {
        480 -> 854
        720 -> 1280
        1080 -> 1920
        else -> (height * 16f / 9f).roundToInt()
    }

    // SMCPKG_SUPPORT>>>Cursor017
    // private fun mosaicPasses(quality: QualityLevel, scaling: Boolean, upscaling: Boolean): List<FilterPass> {
    //     val scale = scalePass(quality != QualityLevel.LOW && upscaling, scaling)
    //     return when (quality) {
    //         QualityLevel.LOW -> listOfNotNull(
    //             FilterPass(FilterType.DEBLOCK, 0.45f, 8f),
    //             scale,
    //             FilterPass(FilterType.SHARPEN, 0.20f),
    //         )
    //         QualityLevel.MEDIUM -> listOfNotNull(
    //             FilterPass(FilterType.DEBLOCK, 0.70f, 8f),
    //             FilterPass(FilterType.EDGE_SMOOTH, 0.65f, 0.08f),
    //             scale,
    //             FilterPass(FilterType.SHARPEN, 0.40f),
    //         )
    //         QualityLevel.HIGH -> listOfNotNull(
    //             FilterPass(FilterType.DEBLOCK, 0.85f, 8f),
    //             FilterPass(FilterType.DEBLOCK, 0.35f, 16f),
    //             FilterPass(FilterType.EDGE_SMOOTH, 0.80f, 0.05f),
    //             scale,
    //             FilterPass(FilterType.SHARPEN, 0.60f),
    //         )
    //     }
    // }
    @Suppress("UNUSED_PARAMETER")
    private fun mosaicPasses(quality: QualityLevel, scaling: Boolean, upscaling: Boolean): List<FilterPass> {
        val level = when (quality) {
            QualityLevel.LOW -> 0f
            QualityLevel.MEDIUM -> 1f
            QualityLevel.HIGH -> 2f
        }
        // Reconstruction samples the source lattice and writes the output size itself.
        val passes = mutableListOf(FilterPass(FilterType.MOSAIC_RECONSTRUCT, level))
        if (quality == QualityLevel.LOW) {
            passes += FilterPass(FilterType.SHARPEN, 0.22f)
        } else {
            passes += FilterPass(FilterType.MOSAIC_TEMPORAL, level)
        }
        return passes
    }
    // SMCPKG_SUPPORT<<<Cursor018

    private fun enhancementPasses(
        level: EnhancementLevel,
        noise: Strength,
        sharpen: Strength,
        scaling: Boolean,
        upscaling: Boolean,
    ): List<FilterPass> {
        val levelScale = when (level) {
            EnhancementLevel.LOW -> 0.70f
            EnhancementLevel.MEDIUM -> 1f
            EnhancementLevel.HIGH -> 1.25f
        }
        val passes = mutableListOf<FilterPass>()
        if (noise != Strength.OFF) {
            val amount = when (noise) {
                Strength.LOW -> 0.35f
                Strength.MEDIUM -> 0.60f
                Strength.HIGH -> 0.85f
                Strength.OFF -> 0f
            } * levelScale
            passes += FilterPass(FilterType.DENOISE, amount.coerceAtMost(1f))
        }
        passes += FilterPass(FilterType.DEBLOCK, (0.40f * levelScale).coerceAtMost(1f), 8f)
        scalePass(level != EnhancementLevel.LOW && upscaling, scaling)?.let { passes += it }
        if (sharpen != Strength.OFF) {
            val amount = when (sharpen) {
                Strength.LOW -> 0.25f
                Strength.MEDIUM -> 0.45f
                Strength.HIGH -> 0.70f
                Strength.OFF -> 0f
            } * levelScale
            passes += FilterPass(FilterType.SHARPEN, amount.coerceAtMost(1.2f))
        }
        val contrast = when (level) {
            EnhancementLevel.LOW -> 1.05f
            EnhancementLevel.MEDIUM -> 1.12f
            EnhancementLevel.HIGH -> 1.20f
        }
        val saturation = when (level) {
            EnhancementLevel.LOW -> 1.04f
            EnhancementLevel.MEDIUM -> 1.10f
            EnhancementLevel.HIGH -> 1.16f
        }
        passes += FilterPass(FilterType.CONTRAST, contrast, saturation)
        return passes
    }

    private fun scalePass(bicubic: Boolean, scaling: Boolean): FilterPass? {
        if (!scaling) return null
        return if (bicubic) {
            FilterPass(FilterType.SCALE_BICUBIC, 1f)
        } else {
            FilterPass(FilterType.SCALE_BILINEAR, 1f)
        }
    }
}
