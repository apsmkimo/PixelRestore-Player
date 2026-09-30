package com.pixelrestore.player.gpu

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.pixelrestore.player.device.FrameTiming
import com.pixelrestore.player.device.PerformanceTracker
import com.pixelrestore.player.processing.FilterPass
import com.pixelrestore.player.processing.FrameHandle
import com.pixelrestore.player.processing.FrameSink
import com.pixelrestore.player.processing.ProcessedFrame
import com.pixelrestore.player.processing.ProcessingBackend
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class OpenGLRenderer(
    private val onTiming: (FrameTiming) -> Unit,
    private val onFatal: (String) -> Unit,
    private val budgetMs: () -> Float,
    private val targetFps: () -> Int,
    private val sourceFps: () -> Float,
) : FrameSink {
    private val egl = EglCore()
    private val shaders = ShaderManager()
    private val textures = TextureManager()
    private val tracker = PerformanceTracker()
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f, 0f,
                    1f, -1f, 0f, 1f, 0f,
                    -1f, 1f, 0f, 0f, 1f,
                    1f, 1f, 0f, 1f, 1f,
                ),
            )
            position(0)
        }

    private var windowSurface = EGL14.EGL_NO_SURFACE
    private var displaySurface: Surface? = null
    var decoderSurfaceTexture: SurfaceTexture? = null
        private set
    var decoderSurface: Surface? = null
        private set
    var maxTextureSize: Int = 0
        private set
    var failure: String? = null
        private set
    val unavailableShaders: List<String> get() = shaders.unavailable.distinct()

    private var viewWidth = 1
    private var viewHeight = 1
    private var lastPts = 0L
    private var lastTimingPost = 0L
    private var released = false
    private var fatalSent = false

    fun init(window: SurfaceTexture, width: Int, height: Int): Boolean {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        if (!egl.init()) {
            failure = egl.failure ?: "EGL init failed"
            return false
        }
        val surface = Surface(window)
        displaySurface = surface
        windowSurface = egl.createWindow(surface)
        if (windowSurface == EGL14.EGL_NO_SURFACE || !egl.makeCurrent(windowSurface)) {
            failure = egl.failure ?: "Unable to bind the display surface"
            return false
        }
        if (!shaders.initialize()) {
            failure = "OpenGL ES blit shader failed to compile"
            return false
        }
        textures.createExternalTexture()
        maxTextureSize = textures.maxTextureSize()
        val decoderTexture = SurfaceTexture(textures.oesTexture)
        decoderTexture.setDefaultBufferSize(viewWidth, viewHeight)
        decoderSurfaceTexture = decoderTexture
        decoderSurface = Surface(decoderTexture)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        clearScreen()
        egl.swap(windowSurface)
        return true
    }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    fun setDecoderBufferSize(width: Int, height: Int) {
        if (width > 1 && height > 1) {
            decoderSurfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    fun drawAvailableFrame() {
        val texture = decoderSurfaceTexture ?: return
        if (windowSurface == EGL14.EGL_NO_SURFACE) return
        try {
            texture.updateTexImage()
            val matrix = FloatArray(16)
            texture.getTransformMatrix(matrix)
            val pts = texture.timestamp
            val dropped = droppedSince(pts)
            lastPts = pts
            val frame = FrameHandle.Gpu(
                oesTextureId = textures.oesTexture,
                transform = matrix,
                sourceWidth = 0,
                sourceHeight = 0,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                presentationTimeNs = pts,
            )
            pendingFrame = frame
            pendingDropped = dropped
            // The active processor calls back into [render] via the sink.
            drawCallback?.invoke(frame, dropped)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Frame draw failed", error)
            failure = error.message
            if (!fatalSent) {
                fatalSent = true
                onFatal(error.message ?: "GPU frame failed")
            }
        }
    }

    private fun normalizePasses(planned: List<GpuPass>, sourceW: Int, sourceH: Int): List<GpuPass> {
        if (planned.size == 1 && planned[0].kind == ShaderKind.BLIT && planned[0].toScreen) {
            return planned
        }
        val copy = GpuPass(
            kind = ShaderKind.BLIT,
            outputWidth = sourceW,
            outputHeight = sourceH,
            strength = 1f,
            secondary = 0f,
            toScreen = false,
        )
        return listOf(copy) + planned
    }

    var drawCallback: ((FrameHandle.Gpu, Int) -> Unit)? = null
    private var pendingFrame: FrameHandle.Gpu? = null
    private var pendingDropped = 0

    override fun render(
        frame: FrameHandle.Gpu,
        passes: List<FilterPass>,
        outputWidth: Int,
        outputHeight: Int,
    ): ProcessedFrame {
        val start = System.nanoTime()
        val sourceW = if (frame.sourceWidth > 1) frame.sourceWidth else outputWidth.coerceAtLeast(2)
        val sourceH = if (frame.sourceHeight > 1) frame.sourceHeight else outputHeight.coerceAtLeast(2)
        val planned = normalizePasses(
            GpuPassPlanner.plan(passes, sourceW, sourceH, outputWidth, outputHeight),
            sourceW,
            sourceH,
        )
        drawPasses(frame, planned, sourceW, sourceH)
        egl.setPresentationTime(windowSurface, frame.presentationTimeNs)
        egl.swap(windowSurface)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000f
        val outW = planned.lastOrNull()?.outputWidth ?: sourceW
        val outH = planned.lastOrNull()?.outputHeight ?: sourceH
        val timing = tracker.record(
            frameTimeMs = elapsedMs,
            droppedDelta = pendingDropped,
            nowNs = System.nanoTime(),
            budgetMs = budgetMs(),
            width = outW,
            height = outH,
        )
        val now = System.nanoTime()
        if (now - lastTimingPost > 200_000_000L) {
            lastTimingPost = now
            onTiming(timing)
        }
        return ProcessedFrame(ProcessingBackend.GPU, outW, outH)
    }

    fun release() {
        if (released) return
        released = true
        try {
            decoderSurface?.release()
            decoderSurfaceTexture?.release()
        } catch (ignored: RuntimeException) {
            Log.w(TAG, "Decoder surface release failed", ignored)
        }
        decoderSurface = null
        decoderSurfaceTexture = null
        shaders.release()
        textures.release()
        egl.releaseSurface(windowSurface)
        windowSurface = EGL14.EGL_NO_SURFACE
        displaySurface?.release()
        displaySurface = null
        egl.release()
    }

    private fun drawPasses(
        frame: FrameHandle.Gpu,
        passes: List<GpuPass>,
        sourceW: Int,
        sourceH: Int,
    ) {
        var readExternal = true
        var readTexture = frame.oesTextureId
        var readW = sourceW
        var readH = sourceH
        var writeSlot = 0
        for (pass in passes) {
            val program = shaders.program(pass.kind, readExternal)
            if (program == null) continue
            if (!shaders.has(pass.kind, readExternal) && pass.kind != ShaderKind.BLIT) {
                // ShaderManager.program falls back to blit. The filter is not running.
            }
            if (pass.toScreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                letterbox(readW, readH)
            } else {
                val fbo = textures.fbo(writeSlot, pass.outputWidth.coerceAtLeast(2), pass.outputHeight.coerceAtLeast(2))
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.framebuffer)
                GLES20.glViewport(0, 0, fbo.width, fbo.height)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                writeSlot = 1 - writeSlot
                // Bind is recorded after draw via the texture id below.
                drawQuad(program, if (readExternal) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, readTexture, if (readExternal) frame.transform else identity, readW, readH, pass)
                readExternal = false
                readTexture = fbo.texture
                readW = fbo.width
                readH = fbo.height
                continue
            }
            drawQuad(
                program,
                if (readExternal) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
                readTexture,
                if (readExternal) frame.transform else identity,
                readW,
                readH,
                pass,
            )
        }
    }

    private fun drawQuad(
        program: ShaderProgram,
        target: Int,
        textureId: Int,
        texMatrix: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        pass: GpuPass,
    ) {
        GLES20.glUseProgram(program.id)
        val position = GLES20.glGetAttribLocation(program.id, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program.id, "aTexCoord")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, STRIDE, quad)
        quad.position(3)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glUniformMatrix4fv(program.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        val texel = program.uniform("uTexelSize")
        if (texel >= 0 && sourceWidth > 0 && sourceHeight > 0) {
            GLES20.glUniform2f(texel, 1f / sourceWidth, 1f / sourceHeight)
        }
        uniform(program, "uStrength", pass.strength)
        uniform(program, "uAmount", pass.strength)
        uniform(program, "uSigma", if (pass.secondary > 0f) pass.secondary else 0.08f)
        uniform(program, "uBlockSize", if (pass.secondary > 1f) pass.secondary else 8f)
        uniform(program, "uContrast", pass.strength)
        uniform(program, "uSaturation", if (pass.secondary > 0f) pass.secondary else 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        val sampler = program.uniform("uTex")
        if (sampler >= 0) GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
        GLES20.glBindTexture(target, 0)
    }

    private fun uniform(program: ShaderProgram, name: String, value: Float) {
        val location = program.uniform(name)
        if (location >= 0) GLES20.glUniform1f(location, value)
    }

    private fun letterbox(srcW: Int, srcH: Int) {
        if (srcW <= 0 || srcH <= 0) {
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            return
        }
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val videoAspect = srcW.toFloat() / srcH.toFloat()
        if (viewAspect > videoAspect) {
            val width = (viewHeight * videoAspect).toInt().coerceAtLeast(1)
            GLES20.glViewport((viewWidth - width) / 2, 0, width, viewHeight)
        } else {
            val height = (viewWidth / videoAspect).toInt().coerceAtLeast(1)
            GLES20.glViewport(0, (viewHeight - height) / 2, viewWidth, height)
        }
    }

    private fun clearScreen() {
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun droppedSince(pts: Long): Int {
        if (lastPts == 0L || pts <= lastPts) return 0
        val fps = sourceFps().takeIf { it > 1f } ?: targetFps().toFloat()
        val expected = 1_000_000_000.0 / fps.coerceAtLeast(1f)
        val gap = pts - lastPts
        if (gap > expected * 1.75) {
            return ((gap / expected) - 1.0).toInt().coerceAtLeast(1)
        }
        return 0
    }

    companion object {
        private const val TAG = "PixelRestore"
        private const val STRIDE = 5 * 4
    }
}
