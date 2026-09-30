package com.pixelrestore.player.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelrestore.player.device.DeviceCapabilities
import com.pixelrestore.player.device.DeviceCapabilityDetector
import com.pixelrestore.player.device.DeviceTier
import com.pixelrestore.player.device.FrameTiming
import com.pixelrestore.player.device.PerformanceAdvisor
import com.pixelrestore.player.player.Media3Player
import com.pixelrestore.player.player.PlaybackState
import com.pixelrestore.player.player.VideoController
import com.pixelrestore.player.processing.AutoAdaptation
import com.pixelrestore.player.processing.FrameHandle
import com.pixelrestore.player.processing.ProcessedFrame
import com.pixelrestore.player.processing.ProcessingBackend
import com.pixelrestore.player.processing.ProcessingManager
import com.pixelrestore.player.processing.ProcessingMode
import com.pixelrestore.player.processing.ProcessingProfileResolver
import com.pixelrestore.player.processing.ResolvedProfile
import com.pixelrestore.player.settings.EnhancementLevel
import com.pixelrestore.player.settings.FrameRateOption
import com.pixelrestore.player.settings.MosaicResolution
import com.pixelrestore.player.settings.OutputResolution
import com.pixelrestore.player.settings.QualityLevel
import com.pixelrestore.player.settings.SettingsRepository
import com.pixelrestore.player.settings.Strength
import com.pixelrestore.player.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val player = Media3Player(application)
    val processingManager: ProcessingManager = ProcessingManager()
    private val controller = VideoController(player, processingManager)

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _capabilities = MutableStateFlow(initialCapabilities())
    val capabilities: StateFlow<DeviceCapabilities> = _capabilities.asStateFlow()

    val playback: StateFlow<PlaybackState> = player.state

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _backend = MutableStateFlow(ProcessingBackend.GPU)
    val backend: StateFlow<ProcessingBackend> = _backend.asStateFlow()

    private val _profile = MutableStateFlow(
        ProcessingProfileResolver.resolve(
            settings = UserSettings(),
            tier = DeviceTier.MID,
            videoWidth = 0,
            videoHeight = 0,
            sourceFps = 0f,
            maxDimension = null,
            adaptation = AutoAdaptation(),
        ),
    )
    val profile: StateFlow<ResolvedProfile> = _profile.asStateFlow()

    private val _timing = MutableStateFlow(FrameTiming(0f, 0f, 0f, 0, false, 0, 0))
    val timing: StateFlow<FrameTiming> = _timing.asStateFlow()

    private val _advice = MutableStateFlow<String?>(null)
    val advice: StateFlow<String?> = _advice.asStateFlow()

    private val _shaderGaps = MutableStateFlow<List<String>>(emptyList())
    val shaderGaps: StateFlow<List<String>> = _shaderGaps.asStateFlow()

    private val _cpuFrame = MutableStateFlow<Bitmap?>(null)
    val cpuFrame: StateFlow<Bitmap?> = _cpuFrame.asStateFlow()

    private val _pipelineNote = MutableStateFlow<String?>(null)
    val pipelineNote: StateFlow<String?> = _pipelineNote.asStateFlow()

    private var latestSettings = UserSettings()
    private var adaptation = AutoAdaptation()
    private var activeSurface: Surface? = null
    private var adaptedForThisOverrun = false
    private var resumeOnStart = false
    private var settingsSeeded = false
    private val settingsWrite = Mutex()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            _capabilities.value = DeviceCapabilityDetector(application).detect()
            resolve()
        }
        viewModelScope.launch {
            repository.settings.collect { incoming ->
                if (!settingsSeeded) {
                    settingsSeeded = true
                    _settings.value = incoming
                    latestSettings = incoming
                    resolve()
                }
            }
        }
        viewModelScope.launch {
            var lastW = 0
            var lastH = 0
            var lastFps = 0f
            playback.collect { state ->
                if (state.videoWidth != lastW || state.videoHeight != lastH || state.frameRate != lastFps) {
                    lastW = state.videoWidth
                    lastH = state.videoHeight
                    lastFps = state.frameRate
                    resolve()
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _positionMs.value = controller.currentPositionMs()
                delay(200)
            }
        }
    }

    fun play(uri: Uri) = controller.play(uri)

    fun togglePlayback() = controller.togglePlayback()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun onHostStart() {
        if (resumeOnStart) controller.playIfReady()
    }

    fun onHostStop() {
        resumeOnStart = playback.value.isPlaying
        controller.pause()
    }

    fun attachVideoSurface(surface: Surface) {
        activeSurface = surface
        controller.attachSurface(surface)
    }

    fun detachVideoSurface(surface: Surface) {
        if (activeSurface === surface) {
            controller.detachSurface(surface)
            activeSurface = null
        }
    }

    fun onGpuUnavailable(reason: String) {
        _backend.value = ProcessingBackend.CPU
        processingManager.setBackend(ProcessingBackend.CPU)
        _pipelineNote.value = "GPU unavailable ($reason). CPU fallback is running at a reduced resolution."
        _shaderGaps.value = emptyList()
    }

    fun onMaxTexture(size: Int) {
        if (size <= 0 || _capabilities.value.maxTextureSize == size) return
        _capabilities.value = _capabilities.value.copy(maxTextureSize = size)
        resolve()
    }

    fun onShaderGaps(names: List<String>) {
        _shaderGaps.value = names.distinct()
    }

    fun onTiming(timing: FrameTiming) {
        _timing.value = timing
        if (!timing.overBudgetSustained) {
            adaptedForThisOverrun = false
            _advice.value = null
            return
        }
        val current = _profile.value
        val stepped = if (!adaptedForThisOverrun) {
            ProcessingProfileResolver.stepDown(
                settings = latestSettings,
                currentHeight = current.outputHeight,
                currentFps = current.targetFps,
                existing = adaptation,
            )
        } else {
            null
        }
        if (stepped != null) {
            adaptedForThisOverrun = true
            adaptation = stepped
            resolve()
            val updated = _profile.value
            _advice.value = PerformanceAdvisor.adaptationNote(updated.outputHeight, updated.targetFps)
        } else if (!adaptedForThisOverrun) {
            _advice.value = PerformanceAdvisor.recommendation(
                current.outputHeight.coerceAtLeast(480),
                current.targetFps,
            )
        }
    }

    fun onCpuFrame(bitmap: Bitmap) {
        val previous = _cpuFrame.value
        _cpuFrame.value = bitmap
        if (previous != null && previous !== bitmap) {
            viewModelScope.launch {
                delay(500)
                if (!previous.isRecycled && _cpuFrame.value !== previous) previous.recycle()
            }
        }
    }

    fun onCpuFailed(message: String) {
        _pipelineNote.value = "CPU fallback failed: $message"
    }

    fun processFrame(frame: FrameHandle): ProcessedFrame = processingManager.process(frame)

    fun setMode(mode: ProcessingMode) = edit { it.copy(mode = mode) }

    fun setMosaicResolution(value: MosaicResolution) = edit { it.copy(mosaicResolution = value) }

    fun setMosaicFrameRate(value: FrameRateOption) = edit { it.copy(mosaicFrameRate = value) }

    fun setMosaicQuality(value: QualityLevel) = edit { it.copy(mosaicQuality = value) }

    fun setEnhancementLevel(value: EnhancementLevel) = edit { it.copy(enhancementLevel = value) }

    fun setEnhancementResolution(value: OutputResolution) = edit { it.copy(enhancementResolution = value) }

    fun setNoiseReduction(value: Strength) = edit { it.copy(noiseReduction = value) }

    fun setSharpening(value: Strength) = edit { it.copy(sharpening = value) }

    fun setAutoOptimization(enabled: Boolean) = edit { it.copy(autoOptimization = enabled) }

    fun setDebugOverlay(enabled: Boolean) = edit { it.copy(debugOverlay = enabled) }

    override fun onCleared() {
        _cpuFrame.value?.takeIf { !it.isRecycled }?.recycle()
        controller.release()
        super.onCleared()
    }

    private fun edit(transform: (UserSettings) -> UserSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        latestSettings = next
        adaptation = AutoAdaptation()
        adaptedForThisOverrun = false
        _advice.value = null
        resolve()
        viewModelScope.launch {
            settingsWrite.withLock {
                val latest = _settings.value
                repository.update { latest }
            }
        }
    }

    private fun resolve() {
        val playbackState = playback.value
        val caps = _capabilities.value
        val resolved = ProcessingProfileResolver.resolve(
            settings = latestSettings,
            tier = caps.tier,
            videoWidth = playbackState.videoWidth,
            videoHeight = playbackState.videoHeight,
            sourceFps = playbackState.frameRate,
            maxDimension = caps.maxProcessingDimension(),
            adaptation = adaptation,
        )
        _profile.value = resolved
        controller.applyProfile(resolved)
    }

    private fun initialCapabilities(): DeviceCapabilities {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return DeviceCapabilities(
            cpuCores = cores,
            cpuArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            ramMb = 0L,
            glEsVersion = null,
            glEsMajor = null,
            vulkanSupported = null,
            hardwareDecoders = emptyList(),
            maxHardwareDimension = null,
            maxTextureSize = null,
            tier = DeviceTier.classify(0L, cores, null),
        )
    }
}
