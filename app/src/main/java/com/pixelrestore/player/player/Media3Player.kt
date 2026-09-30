package com.pixelrestore.player.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.pixelrestore.player.device.DecoderNaming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val decoderName: String = "",
    val decoderHardware: Boolean? = null,
    val decoderDroppedFrames: Int = 0,
    val decoderOffsetMs: Float = 0f,
    val error: String? = null,
    val hasMedia: Boolean = false,
)

/**
 * Local ExoPlayer wrapper. Hardware decoders are preferred; software is only a fallback.
 */
@OptIn(UnstableApi::class)
class Media3Player(context: Context) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(
            DefaultRenderersFactory(appContext)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setMediaCodecSelector(hardwareFirstSelector()),
        )
        .build()
        .also { player ->
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            player.setHandleAudioBecomingNoisy(true)
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            // SMCPKG_SUPPORT>>>Cursor003
            // player.addListener(playerListener)
            // player.addAnalyticsListener(analyticsListener)
            // SMCPKG_SUPPORT<<<Cursor004
        }

    fun play(uri: Uri) {
        _state.value = _state.value.copy(error = null, hasMedia = true, decoderDroppedFrames = 0)
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun togglePlayback() {
        when {
            exoPlayer.playbackState == Player.STATE_ENDED -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
            exoPlayer.isPlaying -> exoPlayer.pause()
            else -> exoPlayer.play()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun playIfReady() {
        exoPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun currentPositionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)

    fun setVideoSurface(surface: Surface?) {
        exoPlayer.setVideoSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        exoPlayer.clearVideoSurface(surface)
    }

    fun release() {
        exoPlayer.removeListener(playerListener)
        exoPlayer.removeAnalyticsListener(analyticsListener)
        exoPlayer.release()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying, durationMs = duration())
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                isPlaying = exoPlayer.isPlaying,
                durationMs = duration(),
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.value = _state.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                error = error.errorCodeName + ": " + (error.message ?: "Playback failed"),
                isPlaying = false,
            )
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _state.value = _state.value.copy(
                decoderName = decoderName,
                decoderHardware = DecoderNaming.isHardware(decoderName),
            )
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            val fps = format.frameRate
            if (fps > 0f) {
                _state.value = _state.value.copy(frameRate = fps)
            }
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int,
        ) {
            if (frameCount <= 0) return
            _state.value = _state.value.copy(decoderOffsetMs = (totalProcessingOffsetUs / frameCount) / 1000f)
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            _state.value = _state.value.copy(
                decoderDroppedFrames = _state.value.decoderDroppedFrames + droppedFrames,
            )
        }
    }

    // SMCPKG_SUPPORT>>>Cursor005
    init {
        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(analyticsListener)
    }
    // SMCPKG_SUPPORT<<<Cursor006

    private fun duration(): Long {
        val value = exoPlayer.duration
        return if (value == C.TIME_UNSET || value < 0L) 0L else value
    }

    private fun hardwareFirstSelector(): MediaCodecSelector {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .sortedByDescending { it.hardwareAccelerated }
        }
    }
}
