package com.pixelrestore.player.device

object PerformanceAdvisor {
    fun recommendation(outputHeight: Int, targetFps: Int): String {
        val suggestH = when {
            outputHeight > 720 -> 720
            outputHeight > 480 -> 480
            else -> 480
        }
        val suggestF = when {
            targetFps > 30 -> 30
            outputHeight > 480 -> 30
            else -> 24
        }
        return "Try ${suggestH}p / $suggestF FPS for smoother playback"
    }

    fun adaptationNote(outputHeight: Int, targetFps: Int): String {
        return "Auto adapted to ${outputHeight}p / $targetFps FPS for smoother playback"
    }
}
