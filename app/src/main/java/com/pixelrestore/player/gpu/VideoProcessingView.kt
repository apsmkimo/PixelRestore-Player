package com.pixelrestore.player.gpu

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import com.pixelrestore.player.device.FrameTiming
import com.pixelrestore.player.processing.FrameHandle
import com.pixelrestore.player.processing.ProcessingManager
import com.pixelrestore.player.processing.ProcessingProfileResolver
import kotlin.math.max
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TextureView so Compose can draw the debug overlay above the picture.
 * Decoder frames land on an OES SurfaceTexture; shaders write the window surface.
 */
class VideoProcessingView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var processingManager: ProcessingManager? = null
    var onDecoderSurface: ((Surface) -> Unit)? = null
    var onDecoderSurfaceDestroyed: ((Surface) -> Unit)? = null
    var onGpuUnavailable: ((String) -> Unit)? = null
    var onMaxTexture: ((Int) -> Unit)? = null
    var onTiming: ((FrameTiming) -> Unit)? = null
    var onShaderGaps: ((List<String>) -> Unit)? = null
    var budgetMs: () -> Float = { 33.3f }
    var targetFps: () -> Int = { 30 }
    var sourceFps: () -> Float = { 0f }
    var sourceWidth: () -> Int = { 0 }
    var sourceHeight: () -> Int = { 0 }

    private val thread = HandlerThread("PixelRestoreGL").also { it.start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(context.mainLooper)
    private var renderer: OpenGLRenderer? = null
    private var released = false

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        handler.post { startGl(surface, width, height) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        handler.post { renderer?.setViewSize(width, height) }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val existing = renderer?.decoderSurface
        if (existing != null) onDecoderSurfaceDestroyed?.invoke(existing)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                renderer?.release()
                renderer = null
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun releaseView() {
        if (released) return
        released = true
        val existing = renderer?.decoderSurface
        if (existing != null) onDecoderSurfaceDestroyed?.invoke(existing)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                renderer?.release()
                renderer = null
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }

    private var lastBufferW = 0
    private var lastBufferH = 0

    fun onVideoSize(width: Int, height: Int) {
        if (width == lastBufferW && height == lastBufferH) return
        lastBufferW = width
        lastBufferH = height
        handler.post {
            val maxEdge = (renderer?.maxTextureSize ?: 0).takeIf { it > 0 } ?: 4096
            val (bufferW, bufferH) = if (max(width, height) > maxEdge) {
                ProcessingProfileResolver.fitEven(width, height, height, maxEdge)
            } else {
                width to height
            }
            renderer?.setDecoderBufferSize(bufferW, bufferH)
        }
    }

    private fun startGl(window: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val created = OpenGLRenderer(
            onTiming = { timing -> mainHandler.post { onTiming?.invoke(timing) } },
            onFatal = { reason -> mainHandler.post { onGpuUnavailable?.invoke(reason) } },
            budgetMs = budgetMs,
            targetFps = targetFps,
            sourceFps = sourceFps,
        )
        processingManager?.attachSink(created)
        // SMCPKG_SUPPORT>>>Cursor007
        // created.drawCallback = { frame, _ ->
        //     val manager = processingManager ?: return@drawCallback
        created.drawCallback = callback@{ frame, _ ->
            val manager = processingManager ?: return@callback
        // SMCPKG_SUPPORT<<<Cursor008
            val sized = FrameHandle.Gpu(
                oesTextureId = frame.oesTextureId,
                transform = frame.transform,
                sourceWidth = sourceWidth().takeIf { it > 1 } ?: frame.viewWidth,
                sourceHeight = sourceHeight().takeIf { it > 1 } ?: frame.viewHeight,
                viewWidth = frame.viewWidth,
                viewHeight = frame.viewHeight,
                presentationTimeNs = frame.presentationTimeNs,
            )
            manager.process(sized)
        }
        if (!created.init(window, width, height)) {
            val reason = created.failure ?: "GPU pipeline unavailable"
            created.release()
            mainHandler.post { onGpuUnavailable?.invoke(reason) }
            return
        }
        renderer = created
        created.decoderSurfaceTexture?.setOnFrameAvailableListener({
            renderer?.drawAvailableFrame()
        }, handler)
        val surface = created.decoderSurface
        val gaps = created.unavailableShaders
        mainHandler.post {
            if (surface != null) onDecoderSurface?.invoke(surface)
            onMaxTexture?.invoke(created.maxTextureSize)
            if (gaps.isNotEmpty()) onShaderGaps?.invoke(gaps)
        }
    }

}
