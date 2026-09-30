package com.pixelrestore.player.device

/**
 * Recommendation tiers. These never overwrite a manual resolution or frame-rate pick.
 * Cutoffs: low &lt; 4 GB or ≤3 cores or GLES &lt; 2; mid otherwise under the high bar;
 * high ≥ 6 GB and ≥ 6 cores; very high ≥ 8 GB, ≥ 8 cores, and GLES 3+.
 * Missing RAM does not promote a device to very high.
 */
enum class DeviceTier(
    val recommendedHeight: Int,
    val recommendedFps: Int,
) {
    LOW(480, 30),
    MID(720, 30),
    HIGH(1080, 30),
    VERY_HIGH(1080, 60),
    ;

    companion object {
        fun classify(ramMb: Long, cores: Int, glEsMajor: Int?): DeviceTier {
            val gl = glEsMajor ?: 2
            if ((ramMb in 1..3499) || cores in 1..3 || gl < 2) return LOW
            if (ramMb >= 8000 && cores >= 8 && gl >= 3) return VERY_HIGH
            if (ramMb >= 6000 && cores >= 6) return HIGH
            if (ramMb <= 0L) return if (cores >= 8) HIGH else MID
            return MID
        }
    }
}
