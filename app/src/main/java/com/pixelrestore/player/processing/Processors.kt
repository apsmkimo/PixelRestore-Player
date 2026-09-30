package com.pixelrestore.player.processing

internal abstract class ConfiguredProcessor(
    override val name: String,
    private val backend: ProcessingBackend,
) : VideoProcessor {
    var sink: FrameSink? = null
    @Volatile var passes: List<FilterPass> = listOf(FilterPass(FilterType.BLIT, 1f))
    @Volatile var outputWidth: Int = 0
    @Volatile var outputHeight: Int = 0
    var releaseCount: Int = 0
        private set
    var mosaicRuntime: MosaicRuntime? = null
    private var active = false

    fun apply(profile: ResolvedProfile) {
        passes = passesFor(profile)
        outputWidth = profile.outputWidth
        outputHeight = profile.outputHeight
    }

    protected abstract fun passesFor(profile: ResolvedProfile): List<FilterPass>

    override fun initialize() {
        active = true
    }

    override fun release() {
        active = false
        releaseCount += 1
    }

    override fun getProcessingBackend(): ProcessingBackend = backend

    override fun processFrame(frame: FrameHandle): ProcessedFrame {
        if (!active) initialize()
        return when (frame) {
            is FrameHandle.Gpu -> sink?.render(frame, passes, outputWidth, outputHeight)
                ?: ProcessedFrame(backend, frame.sourceWidth, frame.sourceHeight)
            is FrameHandle.Cpu -> {
                // SMCPKG_SUPPORT>>>Cursor021
                // val pixels = CpuPixelFilters.apply(frame.pixels, frame.width, frame.height, passes)
                // SMCPKG_SUPPORT>>>Cursor041
                // val pixels = if (passes.any { it.type == FilterType.MOSAIC_RECONSTRUCT } && mosaicRuntime != null) {
                //     mosaicRuntime!!.processCpu(frame.pixels, frame.width, frame.height)
                // } else {
                //     CpuPixelFilters.apply(frame.pixels, frame.width, frame.height, passes)
                // }
                val pixels = when {
                    passes.any { it.type == FilterType.ML_ENHANCE } && mosaicRuntime != null ->
                        mosaicRuntime!!.processMl(frame.pixels, frame.width, frame.height)
                    passes.any { it.type == FilterType.MOSAIC_RECONSTRUCT } && mosaicRuntime != null ->
                        mosaicRuntime!!.processCpu(frame.pixels, frame.width, frame.height)
                    else -> CpuPixelFilters.apply(frame.pixels, frame.width, frame.height, passes)
                }
                // SMCPKG_SUPPORT<<<Cursor042
                // SMCPKG_SUPPORT<<<Cursor022
                ProcessedFrame(ProcessingBackend.CPU, frame.width, frame.height, pixels)
            }
        }
    }
}

internal class PassthroughProcessor : ConfiguredProcessor("PassthroughProcessor", ProcessingBackend.GPU) {
    override fun passesFor(profile: ResolvedProfile): List<FilterPass> = listOf(FilterPass(FilterType.BLIT, 1f))
}

internal class MosaicRestorationProcessor : ConfiguredProcessor(
    "MosaicRestorationProcessor",
    ProcessingBackend.GPU,
) {
    override fun passesFor(profile: ResolvedProfile): List<FilterPass> = profile.passes
}

internal class VideoEnhancementProcessor : ConfiguredProcessor(
    "VideoEnhancementProcessor",
    ProcessingBackend.GPU,
) {
    override fun passesFor(profile: ResolvedProfile): List<FilterPass> = profile.passes
}

internal class CpuFallbackProcessor : ConfiguredProcessor("CpuFallbackProcessor", ProcessingBackend.CPU) {
    override fun passesFor(profile: ResolvedProfile): List<FilterPass> = profile.passes
}

/**
 * Keeps a single processor initialized. Switching mode or backend releases the previous one.
 */
class ProcessingManager {
    val mosaicRuntime: MosaicRuntime = MosaicRuntime()
    private val passthrough = PassthroughProcessor()
    private val mosaic = MosaicRestorationProcessor()
    private val enhancement = VideoEnhancementProcessor()
    private val cpu = CpuFallbackProcessor()
    private val lock = Any()
    private var active: VideoProcessor = passthrough
    var mode: ProcessingMode = ProcessingMode.OFF
        private set
    var backend: ProcessingBackend = ProcessingBackend.GPU
        private set

    fun attachSink(sink: FrameSink) {
        synchronized(lock) {
            passthrough.sink = sink
            mosaic.sink = sink
            enhancement.sink = sink
            sink.bindMosaic(mosaicRuntime)
            mosaic.mosaicRuntime = mosaicRuntime
            cpu.mosaicRuntime = mosaicRuntime
        }
    }

    fun configureMosaic(settings: com.pixelrestore.player.settings.UserSettings) {
        mosaicRuntime.configure(settings)
    }

    fun applyProfile(profile: ResolvedProfile) {
        synchronized(lock) {
            passthrough.apply(profile)
            mosaic.apply(profile)
            enhancement.apply(profile)
            cpu.apply(profile)
            mode = profile.mode
            activate(select(profile.mode, backend))
        }
    }

    fun setBackend(backend: ProcessingBackend) {
        synchronized(lock) {
            this.backend = backend
            activate(select(mode, backend))
        }
    }

    fun process(frame: FrameHandle): ProcessedFrame = synchronized(lock) {
        active.processFrame(frame)
    }

    fun activeProcessorName(): String = synchronized(lock) { active.name }

    fun release() {
        synchronized(lock) {
            active.release()
        }
    }

    private fun select(mode: ProcessingMode, backend: ProcessingBackend): VideoProcessor {
        if (backend == ProcessingBackend.CPU) return cpu
        return when (mode) {
            ProcessingMode.OFF -> passthrough
            ProcessingMode.MOSAIC_RESTORATION -> mosaic
            ProcessingMode.VIDEO_ENHANCEMENT -> enhancement
            // SMCPKG_SUPPORT>>>Cursor043
            // GPU frames stay a blit. PlayerViewModel switches this mode onto the CPU
            // so processMl actually sees pixels.
            ProcessingMode.ML_ENHANCE -> mosaic
            // SMCPKG_SUPPORT<<<Cursor044
        }
    }

    private fun activate(next: VideoProcessor) {
        if (next === active) return
        active.release()
        next.initialize()
        active = next
    }
}
