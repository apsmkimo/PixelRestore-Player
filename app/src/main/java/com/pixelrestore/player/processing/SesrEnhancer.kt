package com.pixelrestore.player.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
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
        if (nchw512.size < SesrTiles.inputFloats()) return null
        val active = session() ?: return null
        return try {
            val buffer = ByteBuffer
                .allocateDirect(nchw512.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(nchw512)
            buffer.rewind()
            val shape = longArrayOf(1, 3, SesrTiles.INPUT.toLong(), SesrTiles.INPUT.toLong())
            OnnxTensor.createTensor(env, buffer, shape).use { input ->
                active.run(mapOf(inputName to input)).use { result ->
                    val tensor = result.get(0) as OnnxTensor
                    val floats = tensor.floatBuffer
                    val out = FloatArray(SesrTiles.outputFloats())
                    floats.rewind()
                    val available = floats.remaining().coerceAtMost(out.size)
                    floats.get(out, 0, available)
                    status = "SESR-M5 2× on CPU"
                    out
                }
            }
        } catch (error: Throwable) {
            status = "SESR-M5 skipped (${error.javaClass.simpleName})"
            null
        }
    }

    private fun session(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        return try {
            val model = assets.open(SesrTiles.ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val created = env.createSession(model, options)
            inputName = created.inputNames.firstOrNull() ?: inputName
            session = created
            status = "SESR-M5 ready"
            created
        } catch (error: Throwable) {
            failed = true
            status = "SESR-M5 unavailable (${error.javaClass.simpleName})"
            null
        }
    }
}
