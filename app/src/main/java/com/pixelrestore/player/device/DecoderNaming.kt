package com.pixelrestore.player.device

object DecoderNaming {
    fun isHardware(decoderName: String): Boolean {
        val name = decoderName.lowercase()
        if (name.isBlank() || name == "—") return false
        if (name.startsWith("omx.google.")) return false
        if (name.startsWith("c2.android.")) return false
        if (name.contains("ffmpeg")) return false
        if (name.endsWith(".sw")) return false
        if (name.contains("software")) return false
        return true
    }

    fun label(decoderName: String, hardware: Boolean?): String {
        if (decoderName.isBlank()) return "—"
        val kind = when (hardware) {
            true -> "HW"
            false -> "SW"
            null -> if (isHardware(decoderName)) "HW" else "SW"
        }
        return "$kind · $decoderName"
    }
}
