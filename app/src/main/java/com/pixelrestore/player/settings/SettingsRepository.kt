package com.pixelrestore.player.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pixelrestore.player.processing.ProcessingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "pixelrestore_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<UserSettings> = context.settingsStore.data
        .map { prefs ->
            UserSettings(
                mode = enumValue(prefs[Keys.MODE], ProcessingMode.OFF),
                mosaicResolution = enumValue(prefs[Keys.MOSAIC_RESOLUTION], MosaicResolution.AUTO),
                mosaicFrameRate = enumValue(prefs[Keys.MOSAIC_FPS], FrameRateOption.AUTO),
                mosaicQuality = enumValue(prefs[Keys.MOSAIC_QUALITY], QualityLevel.MEDIUM),
                enhancementLevel = enumValue(prefs[Keys.ENHANCE_LEVEL], EnhancementLevel.MEDIUM),
                enhancementResolution = enumValue(prefs[Keys.ENHANCE_RESOLUTION], OutputResolution.ORIGINAL),
                noiseReduction = enumValue(prefs[Keys.NOISE], Strength.MEDIUM),
                sharpening = enumValue(prefs[Keys.SHARPEN], Strength.MEDIUM),
                autoOptimization = prefs[Keys.AUTO_OPT] ?: true,
                debugOverlay = prefs[Keys.DEBUG] ?: false,
            )
        }
        .catch { emit(UserSettings()) }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(
                UserSettings(
                    mode = enumValue(prefs[Keys.MODE], ProcessingMode.OFF),
                    mosaicResolution = enumValue(prefs[Keys.MOSAIC_RESOLUTION], MosaicResolution.AUTO),
                    mosaicFrameRate = enumValue(prefs[Keys.MOSAIC_FPS], FrameRateOption.AUTO),
                    mosaicQuality = enumValue(prefs[Keys.MOSAIC_QUALITY], QualityLevel.MEDIUM),
                    enhancementLevel = enumValue(prefs[Keys.ENHANCE_LEVEL], EnhancementLevel.MEDIUM),
                    enhancementResolution = enumValue(prefs[Keys.ENHANCE_RESOLUTION], OutputResolution.ORIGINAL),
                    noiseReduction = enumValue(prefs[Keys.NOISE], Strength.MEDIUM),
                    sharpening = enumValue(prefs[Keys.SHARPEN], Strength.MEDIUM),
                    autoOptimization = prefs[Keys.AUTO_OPT] ?: true,
                    debugOverlay = prefs[Keys.DEBUG] ?: false,
                ),
            )
            prefs[Keys.MODE] = next.mode.name
            prefs[Keys.MOSAIC_RESOLUTION] = next.mosaicResolution.name
            prefs[Keys.MOSAIC_FPS] = next.mosaicFrameRate.name
            prefs[Keys.MOSAIC_QUALITY] = next.mosaicQuality.name
            prefs[Keys.ENHANCE_LEVEL] = next.enhancementLevel.name
            prefs[Keys.ENHANCE_RESOLUTION] = next.enhancementResolution.name
            prefs[Keys.NOISE] = next.noiseReduction.name
            prefs[Keys.SHARPEN] = next.sharpening.name
            prefs[Keys.AUTO_OPT] = next.autoOptimization
            prefs[Keys.DEBUG] = next.debugOverlay
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, default: T): T {
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val MOSAIC_RESOLUTION = stringPreferencesKey("mosaic_resolution")
        val MOSAIC_FPS = stringPreferencesKey("mosaic_fps")
        val MOSAIC_QUALITY = stringPreferencesKey("mosaic_quality")
        val ENHANCE_LEVEL = stringPreferencesKey("enhance_level")
        val ENHANCE_RESOLUTION = stringPreferencesKey("enhance_resolution")
        val NOISE = stringPreferencesKey("noise")
        val SHARPEN = stringPreferencesKey("sharpen")
        val AUTO_OPT = booleanPreferencesKey("auto_optimization")
        val DEBUG = booleanPreferencesKey("debug_overlay")
    }
}
