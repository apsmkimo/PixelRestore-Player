package com.pixelrestore.player.player

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.pixelrestore.player.device.FrameTiming
import com.pixelrestore.player.device.PerformanceTracker
import com.pixelrestore.player.processing.FrameHandle
import com.pixelrestore.player.processing.ProcessedFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CPU fallback: the decoder writes YUV into an ImageReader, then block/denoise/sharpen
 * filters run on a downscaled ARGB buffer. Long edge is capped so the path can keep up.
 */
class CpuVideoOutput(
    private val process: (FrameHandle) -> ProcessedFrame,
    private val onFrame: (Bitmap) -> Unit,
    private val onTiming: (FrameTiming) -> Unit,
    private val onFailed: (String) -> Unit,
    private val budgetMs: () -> Float,
) {
    private val thread = HandlerThread("PixelRestoreCPU").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val tracker = PerformanceTracker()
    private val failed = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var busy = false
    private var skippedWhileBusy = 0

    fun start(width: Int, height: Int): Surface {
        releaseReader()
        val w = even(width)
        val h = even(height)
        val created = ImageReader.newInstance(w, h, android.graphics.ImageFormat.YUV_420_888, 3)
        created.setOnImageAvailableListener({ imageReader -> consume(imageReader) }, handler)
        reader = created
        val output = created.surface
        surface = output
        return output
    }

    fun release() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            releaseReader()
            latch.countDown()
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun consume(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (error: RuntimeException) {
            fail(error.message ?: "Unable to read a CPU frame")
            return
        } ?: return
        if (busy) {
            skippedWhileBusy += 1
            image.close()
            return
        }
        busy = true
        val dropped = skippedWhileBusy
        skippedWhileBusy = 0
        try {
            val argb = YuvFrames.toArgb(image, MAX_EDGE)
            val started = System.nanoTime()
            val processed = process(FrameHandle.Cpu(argb.pixels, argb.width, argb.height))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000f
            val pixels = processed.cpuPixels ?: argb.pixels
            val bitmap = Bitmap.createBitmap(processed.width, processed.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, processed.width, 0, 0, processed.width, processed.height)
            val timing = tracker.record(
                frameTimeMs = elapsedMs,
                droppedDelta = dropped,
                nowNs = System.nanoTime(),
                budgetMs = budgetMs(),
                width = processed.width,
                height = processed.height,
            )
            mainHandler.post {
                onFrame(bitmap)
                onTiming(timing)
            }
        // SMCPKG_SUPPORT>>>Cursor093
        // } catch (error: RuntimeException) {
        //     fail(error.message ?: "CPU processing failed")
        } catch (error: Throwable) {
            fail(error.javaClass.simpleName + ": " + (error.message ?: "CPU processing failed"))
        // SMCPKG_SUPPORT<<<Cursor094
        } finally {
            image.close()
            busy = false
        }
    }

    private fun fail(message: String) {
        if (failed.compareAndSet(false, true)) {
            Log.e(TAG, message)
            mainHandler.post { onFailed(message) }
        }
    }

    private fun releaseReader() {
        surface?.release()
        surface = null
        reader?.close()
        reader = null
    }

    private fun even(value: Int): Int {
        val sized = value.coerceAtLeast(2)
        return if (sized % 2 == 0) sized else sized - 1
    }

    companion object {
        const val MAX_EDGE = 480
        private const val TAG = "PixelRestore"
    }
}
