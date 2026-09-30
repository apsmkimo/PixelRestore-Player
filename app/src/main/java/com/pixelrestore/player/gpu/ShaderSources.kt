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
    MOSAIC_SPATIAL,
    MOSAIC_TEMPORAL,
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
        FilterType.MOSAIC_RECONSTRUCT -> ShaderKind.MOSAIC_SPATIAL
        FilterType.MOSAIC_TEMPORAL -> ShaderKind.MOSAIC_TEMPORAL
        // SMCPKG_SUPPORT>>>Cursor051
        // The network runs on the CPU. A GPU frame only copies the picture.
        FilterType.ML_ENHANCE -> ShaderKind.BLIT
        // SMCPKG_SUPPORT<<<Cursor052
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

        ShaderKind.MOSAIC_SPATIAL -> mosaicSpatial()
        ShaderKind.MOSAIC_TEMPORAL -> mosaicTemporal()
    }

    private fun mosaicSpatial(): String = """
        uniform vec2 uMosaicBlock;
        uniform vec2 uMosaicOffset;
        uniform float uQuality;
        uniform float uDebugView;
        float dist3(vec3 a, vec3 b) { return distance(a, b) / 1.7320508; }
        vec3 blockColor(vec2 b) {
            vec2 center = uMosaicOffset + (b + 0.5) * uMosaicBlock;
            return sampleTex(center * uTexelSize).rgb;
        }
        vec3 catmull(vec3 p0, vec3 p1, vec3 p2, vec3 p3, float t) {
            vec3 c0 = p1;
            vec3 c1 = 0.5 * (p2 - p0);
            vec3 c2 = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
            vec3 c3 = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3;
            return clamp(((c3 * t + c2) * t + c1) * t + c0, 0.0, 1.0);
        }
        bool isStep(float a, float b) {
            float peak = max(a, b);
            float other = min(a, b);
            return peak > 0.18 && (peak - other) > 0.12 && other < peak * 0.55;
        }
        void main() {
            vec2 pix = vTexCoord / uTexelSize;
            vec3 original = sampleTex(vTexCoord).rgb;
            if (uMosaicBlock.x < 1.5 || uMosaicBlock.y < 1.5 || uDebugView > 0.5 && uDebugView < 1.5) {
                gl_FragColor = vec4(original, 1.0);
                return;
            }
            if (uDebugView > 1.5 && uDebugView < 2.5) {
                vec2 local = mod(pix - uMosaicOffset, uMosaicBlock);
                float edge = step(local.x, 1.0) + step(local.y, 1.0);
                vec3 marked = mix(original, vec3(0.25, 0.86, 0.47), clamp(edge, 0.0, 1.0) * 0.7);
                gl_FragColor = vec4(marked, 1.0);
                return;
            }
            vec2 rel = (pix - uMosaicOffset) / uMosaicBlock - 0.5;
            vec2 base = floor(rel);
            vec2 f = rel - base;
            vec3 c00 = blockColor(base);
            vec3 c10 = blockColor(base + vec2(1.0, 0.0));
            vec3 c01 = blockColor(base + vec2(0.0, 1.0));
            vec3 c11 = blockColor(base + vec2(1.0, 1.0));
            vec3 bilinear = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
            vec2 b = floor((pix - uMosaicOffset) / uMosaicBlock);
            vec3 left = blockColor(b + vec2(-1.0, 0.0));
            vec3 mid = blockColor(b);
            vec3 right = blockColor(b + vec2(1.0, 0.0));
            vec3 up = blockColor(b + vec2(0.0, -1.0));
            vec3 down = blockColor(b + vec2(0.0, 1.0));
            float dL = dist3(left, mid);
            float dR = dist3(mid, right);
            float dU = dist3(up, mid);
            float dD = dist3(mid, down);
            bool verticalEdge = isStep(dL, dR);
            bool horizontalEdge = isStep(dU, dD);
            vec3 alongY = mix(blockColor(vec2(b.x, base.y)), blockColor(vec2(b.x, base.y + 1.0)), f.y);
            vec3 alongX = mix(blockColor(vec2(base.x, b.y)), blockColor(vec2(base.x + 1.0, b.y)), f.x);
            vec3 color = bilinear;
            if (uQuality >= 0.5) {
                if (verticalEdge && horizontalEdge) color = mid;
                else if (verticalEdge) color = alongY;
                else if (horizontalEdge) color = alongX;
                else color = bilinear;
                vec2 local = mod(pix - uMosaicOffset, uMosaicBlock);
                float seam = min(min(local.x, uMosaicBlock.x - local.x), min(local.y, uMosaicBlock.y - local.y));
                float edgePeak = max(max(dL, dR), max(dU, dD));
                if (seam <= 1.0 && edgePeak < 0.18) color = mix(color, bilinear, 0.55);
            }
            if (uQuality >= 1.5 && !(verticalEdge || horizontalEdge)) {
                vec3 row0 = catmull(blockColor(base + vec2(-1.0, -1.0)), blockColor(base + vec2(0.0, -1.0)), blockColor(base + vec2(1.0, -1.0)), blockColor(base + vec2(2.0, -1.0)), f.x);
                vec3 row1 = catmull(blockColor(base + vec2(-1.0, 0.0)), c00, c10, blockColor(base + vec2(2.0, 0.0)), f.x);
                vec3 row2 = catmull(blockColor(base + vec2(-1.0, 1.0)), c01, c11, blockColor(base + vec2(2.0, 1.0)), f.x);
                vec3 row3 = catmull(blockColor(base + vec2(-1.0, 2.0)), blockColor(base + vec2(0.0, 2.0)), blockColor(base + vec2(1.0, 2.0)), blockColor(base + vec2(2.0, 2.0)), f.x);
                vec3 cubic = catmull(row0, row1, row2, row3, f.y);
                float edgePeak = max(max(dL, dR), max(dU, dD));
                float keepEdge = clamp((edgePeak - 0.08) / 0.22, 0.0, 1.0);
                color = mix(cubic, color, keepEdge);
            }
            gl_FragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    private fun mosaicTemporal(): String = """
        uniform vec2 uMosaicBlock;
        uniform vec2 uMosaicOffset;
        uniform float uQuality;
        uniform sampler2D uHistory;
        uniform float uHasHistory;
        vec3 hist(vec2 uv) { return texture2D(uHistory, clamp(uv, 0.0, 1.0)).rgb; }
        vec3 blockHere(vec2 b) {
            vec2 center = uMosaicOffset + (b + 0.5) * uMosaicBlock;
            return sampleTex(center * uTexelSize).rgb;
        }
        vec3 blockHist(vec2 b) {
            vec2 center = uMosaicOffset + (b + 0.5) * uMosaicBlock;
            return hist(center * uTexelSize);
        }
        void main() {
            vec3 current = sampleTex(vTexCoord).rgb;
            vec3 color = current;
            if (uHasHistory > 0.5 && uMosaicBlock.x > 1.5) {
                vec2 pix = vTexCoord / uTexelSize;
                vec2 b = floor((pix - uMosaicOffset) / uMosaicBlock);
                float best = 10.0;
                vec2 bestDelta = vec2(0.0);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec2 delta = vec2(float(dx), float(dy));
                        float sad = distance(blockHere(b), blockHist(b + delta));
                        sad += distance(blockHere(b + vec2(1.0, 0.0)), blockHist(b + delta + vec2(1.0, 0.0)));
                        sad += distance(blockHere(b + vec2(0.0, 1.0)), blockHist(b + delta + vec2(0.0, 1.0)));
                        if (sad < best) {
                            best = sad;
                            bestDelta = delta;
                        }
                    }
                }
                if (best < 0.45) {
                    vec2 shifted = vTexCoord - (bestDelta * uMosaicBlock) * uTexelSize;
                    color = mix(current, hist(shifted), 0.40);
                }
            }
            vec3 n = sampleTex(vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
            vec3 s = sampleTex(vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
            vec3 e = sampleTex(vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
            vec3 w = sampleTex(vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
            vec3 blur = (n + s + e + w) * 0.25;
            float amount = uQuality >= 1.5 ? 0.50 : 0.35;
            gl_FragColor = vec4(clamp(color + amount * (color - blur), 0.0, 1.0), 1.0);
        }
    """.trimIndent()
}
