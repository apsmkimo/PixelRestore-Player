package com.pixelrestore.player.gpu

import android.opengl.GLES11Ext
import android.opengl.GLES20
internal class TextureManager {
    class Fbo(
        val framebuffer: Int,
        val texture: Int,
        var width: Int,
        var height: Int,
    )

    private val fbos = arrayOfNulls<Fbo>(4)
    var oesTexture: Int = 0
        private set

    fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return oesTexture
    }

    fun maxTextureSize(): Int {
        val values = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, values, 0)
        return values[0]
    }

    fun fbo(slot: Int, width: Int, height: Int): Fbo {
        val existing = fbos[slot]
        if (existing != null && existing.width == width && existing.height == height) return existing
        existing?.let { delete(it) }
        val textures = IntArray(1)
        val buffers = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glGenFramebuffers(1, buffers, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null as java.nio.Buffer?,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textures[0],
            0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("FBO incomplete 0x${Integer.toHexString(status)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val created = Fbo(buffers[0], textures[0], width, height)
        fbos[slot] = created
        return created
    }

    fun release() {
        fbos.forEach { it?.let(::delete) }
        fbos.fill(null)
        if (oesTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            oesTexture = 0
        }
    }

    private fun delete(fbo: Fbo) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo.framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(fbo.texture), 0)
    }
}
