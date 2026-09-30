package com.pixelrestore.player.processing

enum class ProcessingMode(val label: String) {
    OFF("Off"),
    // SMCPKG_SUPPORT>>>Cursor015
    // MOSAIC_RESTORATION("Mosaic Restoration"),
    MOSAIC_RESTORATION("Mosaic Reconstruction"),
    // SMCPKG_SUPPORT<<<Cursor016
    VIDEO_ENHANCEMENT("Video Enhancement"),
    // SMCPKG_SUPPORT>>>Cursor033
    ML_ENHANCE("ML Enhance (SESR)"),
    // SMCPKG_SUPPORT<<<Cursor034
}

enum class ProcessingBackend(val label: String) {
    GPU("GPU"),
    CPU("CPU"),
}
