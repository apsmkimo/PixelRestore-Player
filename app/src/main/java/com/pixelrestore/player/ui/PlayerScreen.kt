package com.pixelrestore.player.ui

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.pixelrestore.player.R
import com.pixelrestore.player.device.FrameTiming
import com.pixelrestore.player.gpu.VideoProcessingView
import com.pixelrestore.player.player.CpuVideoOutput
import com.pixelrestore.player.player.PlaybackState
import com.pixelrestore.player.processing.MosaicGrid
import com.pixelrestore.player.processing.ProcessingBackend
import com.pixelrestore.player.processing.ProcessingMode
import com.pixelrestore.player.processing.ProcessingProfileResolver
import com.pixelrestore.player.processing.ResolvedProfile
import com.pixelrestore.player.settings.UserSettings

@Composable
fun PlayerScreen(
    settings: UserSettings,
    playback: PlaybackState,
    positionMs: Long,
    profile: ResolvedProfile,
    timing: FrameTiming,
    backend: ProcessingBackend,
    advice: String?,
    shaderGaps: List<String>,
    pipelineNote: String?,
    cpuFrame: android.graphics.Bitmap?,
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onGpuUnavailable: (String) -> Unit,
    onMaxTexture: (Int) -> Unit,
    onShaderGaps: (List<String>) -> Unit,
    onTiming: (FrameTiming) -> Unit,
    onCpuFrame: (android.graphics.Bitmap) -> Unit,
    onCpuFailed: (String) -> Unit,
    processFrame: (com.pixelrestore.player.processing.FrameHandle) -> com.pixelrestore.player.processing.ProcessedFrame,
    processingManager: com.pixelrestore.player.processing.ProcessingManager,
    mosaicGrid: MosaicGrid,
    compareOriginal: Boolean,
    onToggleCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            if (backend == ProcessingBackend.GPU) {
                GpuSurface(
                    playback = playback,
                    profile = profile,
                    processingManager = processingManager,
                    onAttachSurface = onAttachSurface,
                    onDetachSurface = onDetachSurface,
                    onGpuUnavailable = onGpuUnavailable,
                    onMaxTexture = onMaxTexture,
                    onShaderGaps = onShaderGaps,
                    onTiming = onTiming,
                )
            } else {
                cpuFrame?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = stringResource(R.string.video_surface),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                CpuSurface(
                    playback = playback,
                    profile = profile,
                    onAttachSurface = onAttachSurface,
                    onDetachSurface = onDetachSurface,
                    onTiming = onTiming,
                    onCpuFrame = onCpuFrame,
                    onCpuFailed = onCpuFailed,
                    processFrame = processFrame,
                    budgetMs = { profile.budgetMs },
                )
            }
            if (!playback.hasMedia) {
                Text(
                    text = stringResource(R.string.empty_video),
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (settings.debugOverlay || (settings.mosaicDebug && settings.mode == ProcessingMode.MOSAIC_RESTORATION)) {
                DebugOverlay(
                    profile = profile,
                    timing = timing,
                    playback = playback,
                    backend = backend,
                    mosaicGrid = mosaicGrid,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
            // SMCPKG_SUPPORT>>>Cursor053
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = Color.White,
                )
            }
            // SMCPKG_SUPPORT<<<Cursor054
        }

        Text(
            text = statusLine(profile, backend, mosaicGrid),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        pipelineNote?.let {
            Text(text = it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (shaderGaps.isNotEmpty()) {
            Text(
                text = "Not yet implemented on this GPU: ${shaderGaps.joinToString()}",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        advice?.let {
            Text(text = it, color = Color(0xFFFFC857), style = MaterialTheme.typography.bodyMedium)
        }
        playback.error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        SeekRow(
            positionMs = positionMs,
            durationMs = playback.durationMs,
            enabled = playback.hasMedia && playback.durationMs > 0L,
            onSeek = onSeek,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onTogglePlayback, enabled = playback.hasMedia) {
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playback.isPlaying) R.string.pause else R.string.play),
                )
            }
            // SMCPKG_SUPPORT>>>Cursor055
            // if (settings.mode == ProcessingMode.MOSAIC_RESTORATION) {
            if (settings.mode == ProcessingMode.MOSAIC_RESTORATION || settings.mode == ProcessingMode.ML_ENHANCE) {
            // SMCPKG_SUPPORT<<<Cursor056
                Button(onClick = onToggleCompare) {
                    Text(text = stringResource(if (compareOriginal) R.string.compare_processed else R.string.compare_original))
                }
            }
            Button(onClick = onPickVideo) {
                Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                Text(text = stringResource(R.string.pick_video), modifier = Modifier.padding(start = 8.dp))
            }
            // SMCPKG_SUPPORT>>>Cursor057
            // IconButton(onClick = onOpenSettings) {
            //     Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
            // }
            // SMCPKG_SUPPORT<<<Cursor058
        }
    }
}

@Composable
private fun GpuSurface(
    playback: PlaybackState,
    profile: ResolvedProfile,
    processingManager: com.pixelrestore.player.processing.ProcessingManager,
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onGpuUnavailable: (String) -> Unit,
    onMaxTexture: (Int) -> Unit,
    onShaderGaps: (List<String>) -> Unit,
    onTiming: (FrameTiming) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoProcessingView(context).apply {
                this.processingManager = processingManager
                onDecoderSurface = onAttachSurface
                onDecoderSurfaceDestroyed = onDetachSurface
                this.onGpuUnavailable = onGpuUnavailable
                this.onMaxTexture = onMaxTexture
                this.onShaderGaps = onShaderGaps
                this.onTiming = onTiming
            }
        },
        update = { view ->
            view.keepScreenOn = playback.isPlaying
            view.processingManager = processingManager
            view.budgetMs = { profile.budgetMs }
            view.targetFps = { profile.targetFps }
            view.sourceFps = { playback.frameRate }
            view.sourceWidth = { playback.videoWidth }
            view.sourceHeight = { playback.videoHeight }
            if (playback.videoWidth > 1 && playback.videoHeight > 1) {
                view.onVideoSize(playback.videoWidth, playback.videoHeight)
            }
        },
        onRelease = { view -> view.releaseView() },
    )
}

@Composable
private fun CpuSurface(
    playback: PlaybackState,
    profile: ResolvedProfile,
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onTiming: (FrameTiming) -> Unit,
    onCpuFrame: (android.graphics.Bitmap) -> Unit,
    onCpuFailed: (String) -> Unit,
    processFrame: (com.pixelrestore.player.processing.FrameHandle) -> com.pixelrestore.player.processing.ProcessedFrame,
    budgetMs: () -> Float,
) {
    val videoW = playback.videoWidth
    val videoH = playback.videoHeight
    val targetHeight = profile.outputHeight.takeIf { it > 1 } ?: videoH.takeIf { it > 1 } ?: 360
    val (width, height) = if (videoW > 1 && videoH > 1) {
        ProcessingProfileResolver.fitEven(videoW, videoH, targetHeight, CpuVideoOutput.MAX_EDGE)
    } else {
        640 to 360
    }
    val budgetHolder = remember { mutableFloatStateOf(budgetMs()) }
    budgetHolder.floatValue = budgetMs()
    DisposableEffect(width, height) {
        val output = CpuVideoOutput(
            process = processFrame,
            onFrame = onCpuFrame,
            onTiming = onTiming,
            onFailed = onCpuFailed,
            budgetMs = { budgetHolder.floatValue },
        )
        // SMCPKG_SUPPORT>>>Cursor095
        // val surface = output.start(width, height)
        // onAttachSurface(surface)
        val surface = try {
            output.start(width, height)
        } catch (error: Throwable) {
            onCpuFailed(error.javaClass.simpleName + ": " + (error.message ?: "CPU output failed"))
            output.release()
            return@DisposableEffect onDispose { }
        }
        try {
            onAttachSurface(surface)
        } catch (error: Throwable) {
            onCpuFailed(error.javaClass.simpleName + ": " + (error.message ?: "CPU surface failed"))
            output.release()
            return@DisposableEffect onDispose { }
        }
        // SMCPKG_SUPPORT<<<Cursor096
        onDispose {
            onDetachSurface(surface)
            output.release()
        }
    }
}

@Composable
private fun SeekRow(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var seeking by remember { mutableStateOf(false) }
    var preview by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    val sliderValue = if (seeking) preview else positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()
    Column {
        Slider(
            value = sliderValue.coerceIn(0f, duration),
            onValueChange = {
                seeking = true
                preview = it
            },
            onValueChangeFinished = {
                onSeek(preview.toLong())
                seeking = false
            },
            enabled = enabled,
            valueRange = 0f..duration,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatPlaybackTime(if (seeking) preview.toLong() else positionMs))
            Text(text = formatPlaybackTime(durationMs))
        }
    }
}

private fun statusLine(profile: ResolvedProfile, backend: ProcessingBackend, mosaicGrid: MosaicGrid): String {
    val mode = when (profile.mode) {
        ProcessingMode.OFF -> "Off"
        ProcessingMode.MOSAIC_RESTORATION -> {
            "Mosaic Reconstruction (${gridStatus(mosaicGrid)})"
        }
        // SMCPKG_SUPPORT>>>Cursor059
        ProcessingMode.ML_ENHANCE -> "ML Enhance SESR-M5 (${gridStatus(mosaicGrid)})"
        // SMCPKG_SUPPORT<<<Cursor060
        else -> backend.label
    }
    val size = if (profile.outputWidth > 0 && profile.outputHeight > 0) {
        "${profile.outputWidth}×${profile.outputHeight}"
    } else {
        "—"
    }
    val note = mosaicGrid.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "Processing: $mode · $size · ${profile.targetFps} FPS target$note"
}

private fun gridStatus(mosaicGrid: MosaicGrid): String {
    return when {
        mosaicGrid.usable && mosaicGrid.held ->
            "kept ${mosaicGrid.blockWidth}×${mosaicGrid.blockHeight}"
        mosaicGrid.usable ->
            "${mosaicGrid.blockWidth}×${mosaicGrid.blockHeight} @ ${mosaicGrid.offsetX},${mosaicGrid.offsetY}"
        else -> "grid not detected"
    }
}
