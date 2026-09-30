package com.pixelrestore.player

import com.pixelrestore.player.device.DecoderNaming
import com.pixelrestore.player.device.DeviceTier
import com.pixelrestore.player.device.PerformanceAdvisor
import com.pixelrestore.player.device.PerformanceTracker
import com.pixelrestore.player.gpu.GpuPassPlanner
import com.pixelrestore.player.gpu.ShaderKind
import com.pixelrestore.player.gpu.ShaderSources
import com.pixelrestore.player.processing.AutoAdaptation
import com.pixelrestore.player.processing.CpuPixelFilters
import com.pixelrestore.player.processing.FilterPass
import com.pixelrestore.player.processing.FilterType
import com.pixelrestore.player.processing.FrameHandle
import com.pixelrestore.player.processing.FrameSink
import com.pixelrestore.player.processing.ProcessedFrame
import com.pixelrestore.player.processing.ProcessingBackend
import com.pixelrestore.player.processing.ProcessingManager
import com.pixelrestore.player.processing.ProcessingMode
import com.pixelrestore.player.processing.ProcessingProfileResolver
import com.pixelrestore.player.settings.FrameRateOption
import com.pixelrestore.player.settings.MosaicResolution
import com.pixelrestore.player.settings.QualityLevel
import com.pixelrestore.player.settings.Strength
import com.pixelrestore.player.settings.UserSettings
import com.pixelrestore.player.ui.formatPlaybackTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelRestoreLogicTest {
    @Test
    fun tierRecommendationsMatchTheProductExamples() {
        assertEquals(480, DeviceTier.LOW.recommendedHeight)
        assertEquals(30, DeviceTier.LOW.recommendedFps)
        assertEquals(720, DeviceTier.MID.recommendedHeight)
        assertEquals(1080, DeviceTier.HIGH.recommendedHeight)
        assertEquals(30, DeviceTier.HIGH.recommendedFps)
        assertEquals(1080, DeviceTier.VERY_HIGH.recommendedHeight)
        assertEquals(60, DeviceTier.VERY_HIGH.recommendedFps)
        assertEquals(DeviceTier.LOW, DeviceTier.classify(ramMb = 3000, cores = 8, glEsMajor = 3))
        assertEquals(DeviceTier.MID, DeviceTier.classify(ramMb = 4500, cores = 6, glEsMajor = 3))
        assertEquals(DeviceTier.HIGH, DeviceTier.classify(ramMb = 7000, cores = 8, glEsMajor = 3))
        assertEquals(DeviceTier.VERY_HIGH, DeviceTier.classify(ramMb = 12000, cores = 8, glEsMajor = 3))
        assertEquals(DeviceTier.MID, DeviceTier.classify(ramMb = 0, cores = 4, glEsMajor = null))
    }

    @Test
    fun manualResolutionIsNotReplacedByAdaptation() {
        val settings = UserSettings(
            mode = ProcessingMode.MOSAIC_RESTORATION,
            mosaicResolution = MosaicResolution.P1080,
            mosaicFrameRate = FrameRateOption.FPS_60,
        )
        val profile = ProcessingProfileResolver.resolve(
            settings = settings,
            tier = DeviceTier.LOW,
            videoWidth = 854,
            videoHeight = 480,
            sourceFps = 30f,
            maxDimension = 4096,
            adaptation = AutoAdaptation(maxHeight = 480, maxFps = 24),
        )
        assertEquals(1080, profile.outputHeight)
        assertEquals(60, profile.targetFps)
        assertTrue(profile.passes.any { it.type == FilterType.SCALE_BICUBIC || it.type == FilterType.SCALE_BILINEAR })
        assertNull(
            ProcessingProfileResolver.stepDown(
                settings = settings,
                currentHeight = profile.outputHeight,
                currentFps = profile.targetFps,
                existing = AutoAdaptation(),
            ),
        )
    }

    @Test
    fun autoResolutionCanStepDownWithoutTouchingStoredSettings() {
        val settings = UserSettings(
            mode = ProcessingMode.MOSAIC_RESTORATION,
            mosaicResolution = MosaicResolution.AUTO,
            mosaicFrameRate = FrameRateOption.AUTO,
            autoOptimization = true,
        )
        val stepped = ProcessingProfileResolver.stepDown(
            settings = settings,
            currentHeight = 1080,
            currentFps = 30,
            existing = AutoAdaptation(),
        )
        assertEquals(720, stepped?.maxHeight)
        val profile = ProcessingProfileResolver.resolve(
            settings = settings,
            tier = DeviceTier.HIGH,
            videoWidth = 1920,
            videoHeight = 1080,
            sourceFps = 30f,
            maxDimension = 4096,
            adaptation = stepped ?: AutoAdaptation(),
        )
        assertEquals(720, profile.outputHeight)
        assertEquals(MosaicResolution.AUTO, settings.mosaicResolution)
    }

    @Test
    fun modesBuildDistinctRealPasses() {
        val mosaic = ProcessingProfileResolver.resolve(
            settings = UserSettings(
                mode = ProcessingMode.MOSAIC_RESTORATION,
                mosaicQuality = QualityLevel.HIGH,
                mosaicResolution = MosaicResolution.P1080,
            ),
            tier = DeviceTier.MID,
            videoWidth = 640,
            videoHeight = 360,
            sourceFps = 30f,
            maxDimension = 4096,
            adaptation = AutoAdaptation(),
        )
        assertTrue(mosaic.passes.any { it.type == FilterType.DEBLOCK })
        assertTrue(mosaic.passes.any { it.type == FilterType.SCALE_BICUBIC })
        assertFalse(mosaic.passes.any { it.type == FilterType.DENOISE })

        val enhanced = ProcessingProfileResolver.resolve(
            settings = UserSettings(
                mode = ProcessingMode.VIDEO_ENHANCEMENT,
                noiseReduction = Strength.OFF,
                sharpening = Strength.LOW,
            ),
            tier = DeviceTier.MID,
            videoWidth = 1280,
            videoHeight = 720,
            sourceFps = 30f,
            maxDimension = 4096,
            adaptation = AutoAdaptation(),
        )
        assertFalse(enhanced.passes.any { it.type == FilterType.DENOISE })
        assertTrue(enhanced.passes.any { it.type == FilterType.SHARPEN })
        assertTrue(enhanced.passes.any { it.type == FilterType.CONTRAST })

        val off = ProcessingProfileResolver.resolve(
            settings = UserSettings(mode = ProcessingMode.OFF),
            tier = DeviceTier.HIGH,
            videoWidth = 1280,
            videoHeight = 720,
            sourceFps = 24f,
            maxDimension = null,
            adaptation = AutoAdaptation(),
        )
        assertEquals(listOf(FilterType.BLIT), off.passes.map { it.type })
    }

    @Test
    fun onlyOneProcessorIsActive() {
        val seen = mutableListOf<List<FilterType>>()
        val sink = object : FrameSink {
            override fun render(
                frame: FrameHandle.Gpu,
                passes: List<FilterPass>,
                outputWidth: Int,
                outputHeight: Int,
            ): ProcessedFrame {
                seen += passes.map { it.type }
                return ProcessedFrame(ProcessingBackend.GPU, outputWidth, outputHeight)
            }
        }
        val manager = ProcessingManager()
        manager.attachSink(sink)
        val frame = FrameHandle.Gpu(1, FloatArray(16), 320, 180, 320, 180, 0L)
        manager.applyProfile(profile(ProcessingMode.MOSAIC_RESTORATION))
        assertEquals("MosaicRestorationProcessor", manager.activeProcessorName())
        manager.process(frame)
        manager.applyProfile(profile(ProcessingMode.VIDEO_ENHANCEMENT))
        assertEquals("VideoEnhancementProcessor", manager.activeProcessorName())
        manager.process(frame)
        manager.applyProfile(profile(ProcessingMode.OFF))
        assertEquals("PassthroughProcessor", manager.activeProcessorName())
        manager.process(frame)
        manager.setBackend(ProcessingBackend.CPU)
        assertEquals("CpuFallbackProcessor", manager.activeProcessorName())
        assertEquals(3, seen.size)
        assertTrue(seen[0].contains(FilterType.DEBLOCK))
        assertTrue(seen[1].contains(FilterType.CONTRAST))
        assertEquals(listOf(FilterType.BLIT), seen[2])
    }

    @Test
    fun recommendationUsesTheRequestedWording() {
        assertEquals(
            "Try 720p / 30 FPS for smoother playback",
            PerformanceAdvisor.recommendation(outputHeight = 1080, targetFps = 60),
        )
    }

    @Test
    fun sustainedOverrunRequiresTwoSeconds() {
        val tracker = PerformanceTracker()
        val start = 1_000_000_000L
        val early = tracker.record(40f, 0, start, budgetMs = 33.3f, width = 1280, height = 720)
        assertFalse(early.overBudgetSustained)
        val later = tracker.record(40f, 1, start + 2_100_000_000L, budgetMs = 33.3f, width = 1280, height = 720)
        assertTrue(later.overBudgetSustained)
        assertEquals(1, later.droppedFrames)
        val stillMarked = tracker.record(10f, 0, start + 2_200_000_000L, budgetMs = 33.3f, width = 1280, height = 720)
        assertTrue(stillMarked.overBudgetSustained)
        val healed = tracker.record(10f, 0, start + 4_300_000_000L, budgetMs = 33.3f, width = 1280, height = 720)
        assertFalse(healed.overBudgetSustained)
    }

    @Test
    fun cpuDeblockChangesBoundaryPixelsOnly() {
        val width = 16
        val height = 8
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            if (x < 8) argb(0, 0, 0) else argb(255, 255, 255)
        }
        val filtered = CpuPixelFilters.deblock(pixels, width, height, strength = 1f, block = 8)
        assertEquals(pixels[3], filtered[3])
        assertNotEquals(pixels[8], filtered[8])
    }

    @Test
    fun cpuContrastMovesAwayFromMidGray() {
        val mid = argb(160, 160, 160)
        val out = CpuPixelFilters.contrastPixel(mid, contrastValue = 1.2f, saturation = 1f)
        val channel = (out shr 16) and 0xFF
        assertTrue(channel > 160)
    }

    @Test
    fun decoderNamesDistinguishHardware() {
        assertFalse(DecoderNaming.isHardware("OMX.google.h264.decoder"))
        assertFalse(DecoderNaming.isHardware("c2.android.avc.decoder"))
        assertTrue(DecoderNaming.isHardware("c2.qti.avc.decoder"))
        assertEquals("HW · c2.qti.avc.decoder", DecoderNaming.label("c2.qti.avc.decoder", true))
    }

    @Test
    fun shadersAreRealPrograms() {
        ShaderKind.entries.forEach { kind ->
            val source = ShaderSources.fragment(kind, external = false, highp = true)
            assertTrue(source.contains("gl_FragColor"))
            assertTrue(source.contains("sampleTex"))
            assertFalse(source.contains("TODO"))
        }
        val external = ShaderSources.fragment(ShaderKind.BLIT, external = true, highp = false)
        assertTrue(external.contains("samplerExternalOES"))
    }

    @Test
    fun plannerMarksTheLastPassForTheScreen() {
        val passes = GpuPassPlanner.plan(
            filters = listOf(FilterPass(FilterType.DEBLOCK, 0.5f, 8f), FilterPass(FilterType.SHARPEN, 0.3f)),
            sourceWidth = 1920,
            sourceHeight = 1080,
            outputWidth = 1280,
            outputHeight = 720,
        )
        assertTrue(passes.last().toScreen)
        assertTrue(passes.any { it.kind == ShaderKind.BLIT || it.kind == ShaderKind.BICUBIC })
        assertEquals(1280, passes.last().outputWidth)
    }

    @Test
    fun playbackClockFormatsHours() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("1:05", formatPlaybackTime(65_000))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
    }

    private fun profile(mode: ProcessingMode) = ProcessingProfileResolver.resolve(
        settings = UserSettings(
            mode = mode,
            mosaicResolution = MosaicResolution.P720,
            mosaicQuality = QualityLevel.MEDIUM,
            noiseReduction = Strength.MEDIUM,
            sharpening = Strength.MEDIUM,
        ),
        tier = DeviceTier.MID,
        videoWidth = 1280,
        videoHeight = 720,
        sourceFps = 30f,
        maxDimension = 4096,
        adaptation = AutoAdaptation(),
    )

    private fun argb(r: Int, g: Int, b: Int): Int {
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
