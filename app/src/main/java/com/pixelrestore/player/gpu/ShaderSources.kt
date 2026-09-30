package com.pixelrestore.player.gpu

import com.pixelrestore.player.processing.FilterType

internal enum class ShaderKind {
    BLIT,
    DEBLOCK,
    EDGE_SMOOTH,
    BICUBIC,
    SHARPEN,
    DENOISE,
    CONTRAST,
}

internal object ShaderSources {
    const val VERTEX = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    fun fragment(kind: ShaderKind, external: Boolean, highp: Boolean): String {
        val precision = if (highp) "highp" else "mediump"
        val header = if (external) {
            """
            #extension GL_OES_EGL_image_external : require
            precision $precision float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTex;
            vec4 sampleTex(vec2 uv) { return texture2D(uTex, uv); }
            """.trimIndent()
        } else {
            """
            precision $precision float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            vec4 sampleTex(vec2 uv) { return texture2D(uTex, clamp(uv, 0.0, 1.0)); }
            """.trimIndent()
        }
        return header + "\n" + body(kind)
    }

    fun kindFor(type: FilterType): ShaderKind = when (type) {
        FilterType.DEBLOCK -> ShaderKind.DEBLOCK
        FilterType.EDGE_SMOOTH -> ShaderKind.EDGE_SMOOTH
        FilterType.SCALE_BICUBIC -> ShaderKind.BICUBIC
        FilterType.SHARPEN -> ShaderKind.SHARPEN
        FilterType.DENOISE -> ShaderKind.DENOISE
        FilterType.CONTRAST -> ShaderKind.CONTRAST
        FilterType.SCALE_BILINEAR, FilterType.BLIT -> ShaderKind.BLIT
    }

    private fun body(kind: ShaderKind): String = when (kind) {
        ShaderKind.BLIT -> """
            void main() {
                gl_FragColor = sampleTex(vTexCoord);
            }
        """.trimIndent()

        ShaderKind.DEBLOCK -> """
            uniform vec2 uTexelSize;
            uniform float uStrength;
            uniform float uBlockSize;
            void main() {
                vec2 pixel = vTexCoord / uTexelSize;
                vec2 blockPos = mod(pixel, uBlockSize);
                float distEdge = min(
                    min(blockPos.x, uBlockSize - blockPos.x),
                    min(blockPos.y, uBlockSize - blockPos.y));
                vec4 color = sampleTex(vTexCoord);
                if (distEdge < 1.25) {
                    vec2 dir = vec2(0.0);
                    if (blockPos.x < 1.25) dir.x = -1.0;
                    else if (uBlockSize - blockPos.x < 1.25) dir.x = 1.0;
                    if (blockPos.y < 1.25) dir.y = -1.0;
                    else if (uBlockSize - blockPos.y < 1.25) dir.y = 1.0;
                    vec4 across = sampleTex(vTexCoord + dir * uTexelSize * 2.0);
                    float w = clamp(uStrength * (1.25 - distEdge) / 1.25, 0.0, 1.0);
                    color = mix(color, (color + across) * 0.5, w);
                }
                gl_FragColor = color;
            }
        """.trimIndent()

        ShaderKind.EDGE_SMOOTH -> """
            uniform vec2 uTexelSize;
            uniform float uSigma;
            void main() {
                vec4 center = sampleTex(vTexCoord);
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec3 s = sampleTex(vTexCoord + vec2(float(x), float(y)) * uTexelSize).rgb;
                        float spatial = float(x * x + y * y);
                        float colorDist = distance(s, center.rgb);
                        float w = exp(-spatial * 0.35) * exp(-colorDist * colorDist / max(uSigma, 0.0001));
                        sum += s * w;
                        wsum += w;
                    }
                }
                gl_FragColor = vec4(sum / max(wsum, 0.0001), center.a);
            }
        """.trimIndent()

        ShaderKind.DENOISE -> """
            uniform vec2 uTexelSize;
            uniform float uAmount;
            void main() {
                vec4 center = sampleTex(vTexCoord);
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec3 s = sampleTex(vTexCoord + vec2(float(x), float(y)) * uTexelSize).rgb;
                        float colorDist = distance(s, center.rgb);
                        float w = exp(-float(x * x + y * y) * 0.5) * exp(-colorDist * colorDist * 12.0);
                        sum += s * w;
                        wsum += w;
                    }
                }
                vec3 denoise = sum / max(wsum, 0.0001);
                gl_FragColor = vec4(mix(center.rgb, denoise, clamp(uAmount, 0.0, 1.0)), center.a);
            }
        """.trimIndent()

        ShaderKind.SHARPEN -> """
            uniform vec2 uTexelSize;
            uniform float uAmount;
            void main() {
                vec4 c = sampleTex(vTexCoord);
                vec4 n = sampleTex(vTexCoord + vec2(0.0, -uTexelSize.y));
                vec4 s = sampleTex(vTexCoord + vec2(0.0, uTexelSize.y));
                vec4 e = sampleTex(vTexCoord + vec2(uTexelSize.x, 0.0));
                vec4 w = sampleTex(vTexCoord + vec2(-uTexelSize.x, 0.0));
                vec4 blur = (n + s + e + w) * 0.25;
                gl_FragColor = vec4(clamp(c.rgb + uAmount * (c.rgb - blur.rgb), 0.0, 1.0), c.a);
            }
        """.trimIndent()

        ShaderKind.CONTRAST -> """
            uniform float uContrast;
            uniform float uSaturation;
            void main() {
                vec4 c = sampleTex(vTexCoord);
                vec3 rgb = (c.rgb - 0.5) * uContrast + 0.5;
                float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                rgb = mix(vec3(luma), rgb, uSaturation);
                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
        """.trimIndent()

        ShaderKind.BICUBIC -> """
            uniform vec2 uTexelSize;
            void main() {
                vec2 texSize = 1.0 / uTexelSize;
                vec2 coord = vTexCoord * texSize - 0.5;
                vec2 index = floor(coord);
                vec2 f = coord - index;
                vec2 f2 = f * f;
                vec2 f3 = f2 * f;
                vec2 w0 = f2 - 0.5 * (f3 + f);
                vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
                vec2 w2 = -1.5 * f3 + 2.0 * f2 + 0.5 * f;
                vec2 w3 = 0.5 * (f3 - f2);
                vec2 w12 = w1 + w2;
                vec2 offset12 = w2 / max(w12, vec2(0.0001));
                vec2 tex0 = (index - 1.0 + 0.5) * uTexelSize;
                vec2 tex3 = (index + 2.0 + 0.5) * uTexelSize;
                vec2 tex12 = (index + offset12 + 0.5) * uTexelSize;
                vec4 result =
                    sampleTex(vec2(tex0.x, tex0.y)) * w0.x * w0.y +
                    sampleTex(vec2(tex12.x, tex0.y)) * w12.x * w0.y +
                    sampleTex(vec2(tex3.x, tex0.y)) * w3.x * w0.y +
                    sampleTex(vec2(tex0.x, tex12.y)) * w0.x * w12.y +
                    sampleTex(vec2(tex12.x, tex12.y)) * w12.x * w12.y +
                    sampleTex(vec2(tex3.x, tex12.y)) * w3.x * w12.y +
                    sampleTex(vec2(tex0.x, tex3.y)) * w0.x * w3.y +
                    sampleTex(vec2(tex12.x, tex3.y)) * w12.x * w3.y +
                    sampleTex(vec2(tex3.x, tex3.y)) * w3.x * w3.y;
                gl_FragColor = vec4(clamp(result.rgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
