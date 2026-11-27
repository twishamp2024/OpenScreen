package io.github.openscreen.sender.settings

import android.content.Context
import androidx.core.content.edit
import io.github.openscreen.core.capture.FrameRates
import io.github.openscreen.core.capture.QualityPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StreamSettings(
    val preset: QualityPreset = QualityPreset.P1080,
    val fps: Int = FrameRates.DEFAULT,
)

/** Only quality preferences are stored. Pairing data is never persisted. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<StreamSettings> = _settings.asStateFlow()

    fun update(transform: (StreamSettings) -> StreamSettings) {
        val next = transform(_settings.value)
        prefs.edit { putString(KEY_PRESET, next.preset.name).putInt(KEY_FPS, next.fps) }
        _settings.value = next
    }

    private fun load() = StreamSettings(
        preset = prefs.getString(KEY_PRESET, null)
            ?.let { name -> QualityPreset.entries.firstOrNull { it.name == name } }
            ?: QualityPreset.P1080,
        fps = prefs.getInt(KEY_FPS, FrameRates.DEFAULT).let { if (it in FrameRates.ALL) it else FrameRates.DEFAULT },
    )

    private companion object {
        const val KEY_PRESET = "preset"
        const val KEY_FPS = "fps"
    }
}
