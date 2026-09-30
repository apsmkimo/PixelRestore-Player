package com.pixelrestore.player.settings

enum class MosaicResolution(val label: String, val height: Int?) {
    AUTO("Auto", null),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
}

enum class FrameRateOption(val label: String, val fps: Int?) {
    AUTO("Auto", null),
    FPS_24("24", 24),
    FPS_30("30", 30),
    FPS_60("60", 60),
}

enum class QualityLevel(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

enum class EnhancementLevel(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

enum class OutputResolution(val label: String, val height: Int?) {
    ORIGINAL("Original", null),
    P720("720p", 720),
    P1080("1080p", 1080),
}

enum class Strength(val label: String) {
    OFF("Off"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

data class UserSettings(
    val mode: com.pixelrestore.player.processing.ProcessingMode =
        com.pixelrestore.player.processing.ProcessingMode.OFF,
    val mosaicResolution: MosaicResolution = MosaicResolution.AUTO,
    val mosaicFrameRate: FrameRateOption = FrameRateOption.AUTO,
    val mosaicQuality: QualityLevel = QualityLevel.MEDIUM,
    val enhancementLevel: EnhancementLevel = EnhancementLevel.MEDIUM,
    val enhancementResolution: OutputResolution = OutputResolution.ORIGINAL,
    val noiseReduction: Strength = Strength.MEDIUM,
    val sharpening: Strength = Strength.MEDIUM,
    val autoOptimization: Boolean = true,
    val debugOverlay: Boolean = false,
)
