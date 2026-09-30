package com.pixelrestore.player.processing

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device SESR-M5 INT8. The CPU execution provider is the one that runs.
 * NNAPI is not attached: this Q/DQ graph is portable on CPU, and an Android
 * phone is not assumed to have an AMD NPU. A failed session leaves playback
 * on the original pixels.
 */
class SesrEnhancer(private val assets: AssetManager) : TileEnhancer {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var inputName: String = "Model::input_0"
    private var failed = false

    @Volatile var status: String = "SESR-M5 not loaded"
        private set

    override fun enhance(nchw512: FloatArray): FloatArray? {
        if (failed) return null
        if (nchw512.size < SesrTiles.inputFloats()) return null
        val active = session() ?: return null
        // SMCPKG_SUPPORT>>>Cursor087
        // A FloatBuffer view of a direct ByteBuffer can make Android's
        // GetDirectBufferAddress return null. ORT then reads that pointer in
        // session.run and the process dies with a signal try/catch cannot see.
        // Pass the ByteBuffer itself. On failure, stay failed and let the caller
        // use classical mosaic reconstruction.
        // val buffer = ByteBuffer.allocateDirect(...).order(...).asFloatBuffer()
        // OnnxTensor.createTensor(env, buffer, shape)
        return try {
            val bytes = ByteBuffer
                .allocateDirect(nchw512.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            bytes.asFloatBuffer().put(nchw512)
            bytes.rewind()
            val shape = longArrayOf(1, 3, SesrTiles.INPUT.toLong(), SesrTiles.INPUT.toLong())
            OnnxTensor.createTensor(env, bytes, shape, OnnxJavaType.FLOAT).use { input ->
                active.run(mapOf(inputName to input)).use { result ->
                    val tensor = result.get(0) as? OnnxTensor
                        ?: throw IllegalStateException("SESR output was not a tensor")
                    val floats = tensor.floatBuffer
                    val out = FloatArray(SesrTiles.outputFloats())
                    floats.rewind()
                    val available = floats.remaining()
                    if (available < out.size) {
                        throw IllegalStateException("SESR output has $available floats")
                    }
                    floats.get(out, 0, out.size)
                    status = "SESR-M5 2× on CPU"
                    out
                }
            }
        } catch (error: Throwable) {
            failed = true
            status = "SESR-M5 skipped (${error.javaClass.simpleName})"
            Log.e(TAG, status, error)
            null
        }
        // SMCPKG_SUPPORT<<<Cursor088
    }

    private fun session(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        return try {
            val model = assets.open(SesrTiles.ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions()
            // SMCPKG_SUPPORT>>>Cursor089
            // options.setIntraOpNumThreads(2)
            // options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            // SMCPKG_SUPPORT<<<Cursor090
            val created = env.createSession(model, options)
            options.close()
            inputName = created.inputNames.firstOrNull() ?: inputName
            val info = created.inputInfo[inputName] as? TensorInfo
            val shape = info?.shape
            val expected = longArrayOf(1, 3, SesrTiles.INPUT.toLong(), SesrTiles.INPUT.toLong())
            if (shape == null || !shape.contentEquals(expected)) {
                created.close()
                failed = true
                status = "SESR-M5 input shape ${shape?.contentToString()} is not 1x3x512x512"
                Log.e(TAG, status)
                return null
            }
            session = created
            status = "SESR-M5 ready"
            created
        } catch (error: Throwable) {
            failed = true
            status = "SESR-M5 unavailable (${error.javaClass.simpleName})"
            Log.e(TAG, status, error)
            null
        }
    }

    private companion object {
        const val TAG = "PixelRestore"
    }
}
