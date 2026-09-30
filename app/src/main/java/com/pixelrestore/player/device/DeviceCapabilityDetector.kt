package com.pixelrestore.player.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

data class DecoderSummary(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
)

data class DeviceCapabilities(
    val cpuCores: Int,
    val cpuArch: String,
    val androidRelease: String,
    val sdkInt: Int,
    val ramMb: Long,
    val glEsVersion: String?,
    val glEsMajor: Int?,
    val vulkanSupported: Boolean?,
    val hardwareDecoders: List<DecoderSummary>,
    val maxHardwareDimension: Int?,
    val maxTextureSize: Int?,
    val tier: DeviceTier,
) {
    fun maxProcessingDimension(): Int? {
        val texture = maxTextureSize
        val codec = maxHardwareDimension
        return when {
            texture != null && codec != null -> minOf(texture, codec)
            texture != null -> texture
            codec != null -> codec
            else -> null
        }
    }

    fun summary(): String {
        val ram = if (ramMb > 0) "$ramMb MB RAM" else "RAM unknown"
        val gl = glEsVersion ?: "GLES unknown"
        val vulkan = when (vulkanSupported) {
            true -> "Vulkan yes"
            false -> "Vulkan no"
            null -> "Vulkan unknown"
        }
        return "$cpuCores cores · $cpuArch · $ram · $gl · $vulkan · tier ${tier.name}"
    }
}

class DeviceCapabilityDetector(private val context: Context) {
    fun detect(): DeviceCapabilities {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val ramMb = readRamMb()
        val (glVersion, glMajor) = readGlEs()
        val vulkan = readVulkan()
        val decoders = readDecoders()
        val maxHardware = decoders.filter { it.hardware }
            .maxOfOrNull { maxOf(it.maxWidth, it.maxHeight) }
            ?.takeIf { it > 0 }
        val tier = DeviceTier.classify(ramMb, cores, glMajor)
        return DeviceCapabilities(
            cpuCores = cores,
            cpuArch = arch,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            ramMb = ramMb,
            glEsVersion = glVersion,
            glEsMajor = glMajor,
            vulkanSupported = vulkan,
            hardwareDecoders = decoders.filter { it.hardware }.take(8),
            maxHardwareDimension = maxHardware,
            maxTextureSize = null,
            tier = tier,
        )
    }

    private fun readRamMb(): Long {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        } catch (error: RuntimeException) {
            Log.w(TAG, "RAM unavailable", error)
            0L
        }
    }

    private fun readGlEs(): Pair<String?, Int?> {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = manager.deviceConfigurationInfo ?: return null to null
            val encoded = info.reqGlEsVersion
            if (encoded == 0) return info.glEsVersion to null
            val major = encoded shr 16
            val minor = encoded and 0xFFFF
            "$major.$minor" to major
        } catch (error: RuntimeException) {
            Log.w(TAG, "GLES version unavailable", error)
            null to null
        }
    }

    private fun readVulkan(): Boolean? {
        return try {
            // SMCPKG_SUPPORT>>>Cursor009
            // context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN)
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            // SMCPKG_SUPPORT<<<Cursor010
        } catch (error: RuntimeException) {
            Log.w(TAG, "Vulkan feature unavailable", error)
            null
        }
    }

    private fun readDecoders(): List<DecoderSummary> {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val found = ArrayList<DecoderSummary>()
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (mime in info.supportedTypes) {
                    if (!mime.startsWith("video/")) continue
                    if (mime !in INTERESTING) continue
                    val hardware = isHardware(info)
                    val (maxW, maxH) = try {
                        val caps = info.getCapabilitiesForType(mime).videoCapabilities
                        (caps?.supportedWidths?.upper ?: 0) to (caps?.supportedHeights?.upper ?: 0)
                    } catch (error: RuntimeException) {
                        0 to 0
                    }
                    found += DecoderSummary(info.name, mime, hardware, maxW, maxH)
                }
            }
            found
        } catch (error: RuntimeException) {
            Log.w(TAG, "Codec scan failed", error)
            emptyList()
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            DecoderNaming.isHardware(info.name)
        }
    }

    companion object {
        private const val TAG = "PixelRestore"
        private val INTERESTING = setOf(
            "video/avc",
            "video/hevc",
            "video/x-vnd.on2.vp9",
            "video/av01",
        )
    }
}
