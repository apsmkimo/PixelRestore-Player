package com.pixelrestore.player.gpu

import android.opengl.GLES20
import android.util.Log

internal class ShaderProgram(
    val id: Int,
    private val uniforms: Map<String, Int>,
) {
    fun uniform(name: String): Int = uniforms[name] ?: -1
}

internal class ShaderManager {
    private val programs = HashMap<String, ShaderProgram>()
    val unavailable = mutableListOf<String>()

    fun initialize(): Boolean {
        // The decoder image is sampled once with an external-OES blit. Later passes use sampler2D.
        val externalBlit = compile(ShaderKind.BLIT, external = true)
        if (externalBlit == null) return false
        programs[key(ShaderKind.BLIT, true)] = externalBlit
        for (kind in ShaderKind.entries) {
            val program = compile(kind, external = false)
            if (program != null) {
                programs[key(kind, false)] = program
            } else if (kind != ShaderKind.BLIT) {
                unavailable += kind.name
            }
        }
        return programs.containsKey(key(ShaderKind.BLIT, false))
    }

    fun program(kind: ShaderKind, external: Boolean): ShaderProgram? {
        return programs[key(kind, external)] ?: programs[key(ShaderKind.BLIT, external)]
    }

    fun has(kind: ShaderKind, external: Boolean): Boolean = programs.containsKey(key(kind, external))

    fun release() {
        programs.values.forEach { GLES20.glDeleteProgram(it.id) }
        programs.clear()
    }

    private fun key(kind: ShaderKind, external: Boolean): String = "${kind.name}:${if (external) "oes" else "2d"}"

    private fun compile(kind: ShaderKind, external: Boolean): ShaderProgram? {
        val high = link(kind, external, highp = true)
        if (high != null) return high
        return link(kind, external, highp = false)
    }

    private fun link(kind: ShaderKind, external: Boolean, highp: Boolean): ShaderProgram? {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, ShaderSources.VERTEX) ?: return null
        val fragmentSource = ShaderSources.fragment(kind, external, highp)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragment == null) {
            GLES20.glDeleteShader(vertex)
            return null
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            Log.w(TAG, "Link ${kind.name} external=$external: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return null
        }
        val names = arrayOf(
            "uTex", "uTexMatrix", "uTexelSize", "uStrength", "uBlockSize",
            "uSigma", "uAmount", "uContrast", "uSaturation",
        )
        val locations = names.associateWith { GLES20.glGetUniformLocation(program, it) }
        return ShaderProgram(program, locations)
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    companion object {
        private const val TAG = "PixelRestore"
    }
}
