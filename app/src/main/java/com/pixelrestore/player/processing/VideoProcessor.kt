package com.pixelrestore.player.processing

/**
 * A decoded frame. GPU frames stay on the GPU (external OES texture).
 * CPU frames are an ARGB buffer used only when the GL path is unavailable.
 */
sealed class FrameHandle {
    class Gpu(
        val oesTextureId: Int,
        val transform: FloatArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val presentationTimeNs: Long,
    ) : FrameHandle()

    class Cpu(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
    ) : FrameHandle()
}

class ProcessedFrame(
    val backend: ProcessingBackend,
    val width: Int,
    val height: Int,
    val cpuPixels: IntArray? = null,
)

/**
 * Renders a GPU frame through [passes]. Implemented by the OpenGL renderer
 * so processors stay free of EGL types.
 */
interface FrameSink {
    fun render(
        frame: FrameHandle.Gpu,
        passes: List<FilterPass>,
        outputWidth: Int,
        outputHeight: Int,
    ): ProcessedFrame
}

interface VideoProcessor {
    fun initialize()
    fun processFrame(frame: FrameHandle): ProcessedFrame
    fun release()
    fun getProcessingBackend(): ProcessingBackend
    val name: String
}
