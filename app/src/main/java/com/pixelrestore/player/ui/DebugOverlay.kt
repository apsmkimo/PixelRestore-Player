package com.pixelrestore.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelrestore.player.device.DecoderNaming
import com.pixelrestore.player.device.FrameTiming
import com.pixelrestore.player.player.PlaybackState
import com.pixelrestore.player.processing.ProcessingBackend
import com.pixelrestore.player.processing.ResolvedProfile

@Composable
fun DebugOverlay(
    profile: ResolvedProfile,
    timing: FrameTiming,
    playback: PlaybackState,
    backend: ProcessingBackend,
    modifier: Modifier = Modifier,
) {
    val width = timing.actualWidth.takeIf { it > 0 } ?: profile.outputWidth
    val height = timing.actualHeight.takeIf { it > 0 } ?: profile.outputHeight
    val dropped = timing.droppedFrames + playback.decoderDroppedFrames
    val pipeline = if (backend == ProcessingBackend.GPU) "OpenGL ES 2.0" else "CPU YUV"
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(8.dp),
    ) {
        DebugLine("Resolution: ${width}×${height}")
        DebugLine("FPS: target ${profile.targetFps} / actual ${"%.1f".format(timing.actualFps)}")
        DebugLine("Dropped frames: $dropped")
        DebugLine("Decoder: ${DecoderNaming.label(playback.decoderName, playback.decoderHardware)}")
        DebugLine("Processing: ${backend.label}")
        DebugLine("Frame time: ${"%.1f".format(timing.frameTimeMs)} ms")
        DebugLine("Pipeline: $pipeline")
    }
}

@Composable
private fun DebugLine(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}
