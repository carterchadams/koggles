package dev.koggles.ui.menu

import dev.koggles.audio.AudioEvent
import dev.koggles.playback.PlaybackControls
import dev.koggles.presets.PresetLibrary
import dev.koggles.settings.Settings
import dev.koggles.settings.SettingsValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Everything the menu sections act on. */
class MenuActions(
    val playback: PlaybackControls,
    val library: PresetLibrary,
    private val settings: Settings,
    private val scope: CoroutineScope,
    val dispatchAudio: (AudioEvent) -> Unit,
    val onChooseFolder: () -> Unit,
    val onRescan: () -> Unit,
    val onScanQr: () -> Unit,
) {
    fun updateSettings(f: (SettingsValues) -> SettingsValues) {
        scope.launch { settings.update(f) }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
