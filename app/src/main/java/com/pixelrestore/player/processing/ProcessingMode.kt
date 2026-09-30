package com.pixelrestore.player.processing

enum class ProcessingMode(val label: String) {
    OFF("Off"),
    MOSAIC_RESTORATION("Mosaic Restoration"),
    VIDEO_ENHANCEMENT("Video Enhancement"),
}

enum class ProcessingBackend(val label: String) {
    GPU("GPU"),
    CPU("CPU"),
}
