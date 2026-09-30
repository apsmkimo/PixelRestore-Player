package com.pixelrestore.player.player

import android.net.Uri
import android.view.Surface
import com.pixelrestore.player.processing.ProcessingManager
import com.pixelrestore.player.processing.ResolvedProfile

/**
 * Facade the UI uses so playback and the single active processor stay in step.
 */
class VideoController(
    private val player: Media3Player,
    private val processingManager: ProcessingManager,
) {
    fun play(uri: Uri) = player.play(uri)

    fun togglePlayback() = player.togglePlayback()

    fun pause() = player.pause()

    fun playIfReady() = player.playIfReady()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun currentPositionMs(): Long = player.currentPositionMs()

    fun attachSurface(surface: Surface) = player.setVideoSurface(surface)

    fun detachSurface(surface: Surface) = player.clearVideoSurface(surface)

    fun applyProfile(profile: ResolvedProfile) = processingManager.applyProfile(profile)

    fun release() {
        processingManager.release()
        player.release()
    }
}
