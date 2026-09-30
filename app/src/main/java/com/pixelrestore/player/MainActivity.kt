package com.pixelrestore.player

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixelrestore.player.ui.PlayerScreen
import com.pixelrestore.player.ui.PlayerViewModel
import com.pixelrestore.player.ui.SettingsScreen
import com.pixelrestore.player.ui.theme.PixelRestoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val viewModel: PlayerViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val playback by viewModel.playback.collectAsStateWithLifecycle()
            val position by viewModel.positionMs.collectAsStateWithLifecycle()
            val profile by viewModel.profile.collectAsStateWithLifecycle()
            val timing by viewModel.timing.collectAsStateWithLifecycle()
            val backend by viewModel.backend.collectAsStateWithLifecycle()
            val advice by viewModel.advice.collectAsStateWithLifecycle()
            val shaderGaps by viewModel.shaderGaps.collectAsStateWithLifecycle()
            val pipelineNote by viewModel.pipelineNote.collectAsStateWithLifecycle()
            val cpuFrame by viewModel.cpuFrame.collectAsStateWithLifecycle()
            val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
            var showSettings by rememberSaveable { mutableStateOf(false) }
            val context = LocalContext.current
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (error: SecurityException) {
                        Log.w(TAG, "URI permission is session-only", error)
                    }
                    viewModel.play(uri)
                }
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, viewModel) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> viewModel.onHostStart()
                        Lifecycle.Event.ON_STOP -> viewModel.onHostStop()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            PixelRestoreTheme {
                Scaffold { padding ->
                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            capabilities = capabilities,
                            onBack = { showSettings = false },
                            modifier = Modifier.padding(padding),
                            onMode = viewModel::setMode,
                            onMosaicResolution = viewModel::setMosaicResolution,
                            onMosaicFrameRate = viewModel::setMosaicFrameRate,
                            onMosaicQuality = viewModel::setMosaicQuality,
                            onEnhancementLevel = viewModel::setEnhancementLevel,
                            onEnhancementResolution = viewModel::setEnhancementResolution,
                            onNoise = viewModel::setNoiseReduction,
                            onSharpen = viewModel::setSharpening,
                            onAutoOptimization = viewModel::setAutoOptimization,
                            onDebugOverlay = viewModel::setDebugOverlay,
                        )
                    } else {
                        PlayerScreen(
                            settings = settings,
                            playback = playback,
                            positionMs = position,
                            profile = profile,
                            timing = timing,
                            backend = backend,
                            advice = advice,
                            shaderGaps = shaderGaps,
                            pipelineNote = pipelineNote,
                            cpuFrame = cpuFrame,
                            onPickVideo = { picker.launch(arrayOf("video/*")) },
                            onOpenSettings = { showSettings = true },
                            onTogglePlayback = viewModel::togglePlayback,
                            onSeek = viewModel::seekTo,
                            onAttachSurface = viewModel::attachVideoSurface,
                            onDetachSurface = viewModel::detachVideoSurface,
                            onGpuUnavailable = viewModel::onGpuUnavailable,
                            onMaxTexture = viewModel::onMaxTexture,
                            onShaderGaps = viewModel::onShaderGaps,
                            onTiming = viewModel::onTiming,
                            onCpuFrame = viewModel::onCpuFrame,
                            onCpuFailed = viewModel::onCpuFailed,
                            processFrame = viewModel::processFrame,
                            processingManager = viewModel.processingManager,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PixelRestore"
    }
}
