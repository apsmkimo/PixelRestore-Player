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
                val pixels = CpuPixelFilters.apply(frame.pixels, frame.width, frame.height, passes)
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
        }
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
        }
    }

    private fun activate(next: VideoProcessor) {
        if (next === active) return
        active.release()
        next.initialize()
        active = next
    }
}
