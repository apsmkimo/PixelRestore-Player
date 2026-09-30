package com.pixelrestore.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixelrestore.player.BuildConfig
import com.pixelrestore.player.R
import com.pixelrestore.player.device.DeviceCapabilities
import com.pixelrestore.player.processing.ProcessingMode
import com.pixelrestore.player.processing.ProcessingProfileResolver
import com.pixelrestore.player.settings.EnhancementLevel
import com.pixelrestore.player.settings.FrameRateOption
import com.pixelrestore.player.settings.MosaicResolution
import com.pixelrestore.player.settings.OutputResolution
import com.pixelrestore.player.settings.QualityLevel
import com.pixelrestore.player.settings.Strength
import com.pixelrestore.player.settings.UserSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    capabilities: DeviceCapabilities,
    onBack: () -> Unit,
    onMode: (ProcessingMode) -> Unit,
    onMosaicResolution: (MosaicResolution) -> Unit,
    onMosaicFrameRate: (FrameRateOption) -> Unit,
    onMosaicQuality: (QualityLevel) -> Unit,
    onEnhancementLevel: (EnhancementLevel) -> Unit,
    onEnhancementResolution: (OutputResolution) -> Unit,
    onNoise: (Strength) -> Unit,
    onSharpen: (Strength) -> Unit,
    onAutoOptimization: (Boolean) -> Unit,
    onDebugOverlay: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val supported = ProcessingProfileResolver.supportedHeights(capabilities.maxProcessingDimension())
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.processing_mode), style = MaterialTheme.typography.titleMedium)
            ProcessingMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.mode == mode,
                        onClick = { onMode(mode) },
                    )
                    Text(mode.label)
                }
            }

            if (settings.mode == ProcessingMode.MOSAIC_RESTORATION) {
                HorizontalDivider()
                Text(stringResource(R.string.mosaic_section), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.mosaic_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionRow(
                    title = stringResource(R.string.resolution),
                    options = MosaicResolution.entries.filter { option ->
                        option.height == null || option.height in supported
                    },
                    selected = settings.mosaicResolution,
                    label = { it.label },
                    onSelect = onMosaicResolution,
                )
                OptionRow(
                    title = stringResource(R.string.frame_rate),
                    options = FrameRateOption.entries,
                    selected = settings.mosaicFrameRate,
                    label = { it.label },
                    onSelect = onMosaicFrameRate,
                )
                OptionRow(
                    title = stringResource(R.string.quality),
                    options = QualityLevel.entries,
                    selected = settings.mosaicQuality,
                    label = { it.label },
                    onSelect = onMosaicQuality,
                )
            }

            if (settings.mode == ProcessingMode.VIDEO_ENHANCEMENT) {
                HorizontalDivider()
                Text(stringResource(R.string.enhancement_section), style = MaterialTheme.typography.titleMedium)
                OptionRow(
                    title = stringResource(R.string.enhancement_level),
                    options = EnhancementLevel.entries,
                    selected = settings.enhancementLevel,
                    label = { it.label },
                    onSelect = onEnhancementLevel,
                )
                OptionRow(
                    title = stringResource(R.string.output_resolution),
                    options = OutputResolution.entries.filter { option ->
                        option.height == null || option.height in supported
                    },
                    selected = settings.enhancementResolution,
                    label = { it.label },
                    onSelect = onEnhancementResolution,
                )
                OptionRow(
                    title = stringResource(R.string.noise_reduction),
                    options = Strength.entries,
                    selected = settings.noiseReduction,
                    label = { it.label },
                    onSelect = onNoise,
                )
                OptionRow(
                    title = stringResource(R.string.sharpening),
                    options = Strength.entries,
                    selected = settings.sharpening,
                    label = { it.label },
                    onSelect = onSharpen,
                )
            }

            HorizontalDivider()
            Text(stringResource(R.string.performance_section), style = MaterialTheme.typography.titleMedium)
            SettingSwitch(
                title = stringResource(R.string.auto_optimization),
                hint = stringResource(R.string.auto_optimization_hint),
                checked = settings.autoOptimization,
                onChecked = onAutoOptimization,
            )
            SettingSwitch(
                title = stringResource(R.string.debug_overlay),
                hint = stringResource(R.string.debug_overlay_hint),
                checked = settings.debugOverlay,
                onChecked = onDebugOverlay,
            )
            Text(
                text = "Recommended starting point: ${capabilities.tier.recommendedHeight}p / ${capabilities.tier.recommendedFps} FPS (${capabilities.tier.name.lowercase()} tier). Manual picks are not changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = capabilities.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(stringResource(R.string.about_section), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
            Text("Version ${BuildConfig.VERSION_NAME}")
            Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.secondary)
            Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mosaic_disclaimer), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.no_ai), style = MaterialTheme.typography.bodySmall)
            if (capabilities.hardwareDecoders.isNotEmpty()) {
                Text(
                    text = "Hardware decoders: " + capabilities.hardwareDecoders
                        .map { it.mime.removePrefix("video/") }
                        .distinct()
                        .joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "Vulkan is detected for the capability report and is not used for rendering in this version. The active pipeline is OpenGL ES, with a CPU fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> OptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
