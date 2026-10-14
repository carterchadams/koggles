package dev.koggles.ui.menu

import dev.koggles.audio.AudioEvent
import dev.koggles.playback.PlaybackControls
import dev.koggles.presets.PresetLibrary
import dev.koggles.settings.Settings
import dev.koggles.settings.SettingsValues
import dev.koggles.telemetry.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Everything the menu sections act on. [scope] must outlive the menu (writes are not cancelled by closing it). */
class MenuActions(
    val playback: PlaybackControls,
    val library: PresetLibrary,
    private val settings: Settings,
    private val scope: CoroutineScope,
    val dispatchAudio: (AudioEvent) -> Unit,
    val onChooseFolder: () -> Unit,
    val onRescan: () -> Unit,
    val onScanQr: () -> Unit,
    /** Spacing calibration: closes the menu and shows the bullseyes (the volume keys then step the spacing). */
    val onCalibrateSpacing: () -> Unit,
    /** Anonymous usage statistics: reports an event (dropped unless the user turned the switch on). */
    val track: (Event) -> Unit,
    /** Flips the analytics switch: the gate first, then the setting (AppGraph.setAnalytics). */
    val onAnalytics: (Boolean) -> Unit,
) {
    fun updateSettings(f: (SettingsValues) -> SettingsValues) {
        scope.launch { settings.update(f) }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
