package dev.koggles.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import dev.koggles.presets.PresetRef
import dev.koggles.settings.PRESET_DURATIONS
import dev.koggles.settings.Scope
import dev.koggles.settings.SettingsValues

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.playbackSection(v: SettingsValues, current: PresetRef?, actions: MenuActions) {
    item(key = "playback-header") { SectionHeader("Playback") }
    item(key = "playback-duration") {
        Text("Auto-advance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (sec in PRESET_DURATIONS) {
                FilterChip(
                    selected = v.presetDurationSec == sec,
                    onClick = { actions.updateSettings { it.copy(presetDurationSec = sec) } },
                    label = { Text(if (sec == 0) "Off" else "$sec s") },
                )
            }
        }
    }
    item(key = "playback-transition") {
        Text("Transition")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(v.softCut, { actions.updateSettings { it.copy(softCut = true) } }, { Text("Soft blend") })
            FilterChip(!v.softCut, { actions.updateSettings { it.copy(softCut = false) } }, { Text("Hard cut") })
        }
        if (v.softCut) {
            StepSlider("Blend", v.blendSec.toFloat(), 1f..10f, 0.5f, { "%.1f s".format(it) }) { x ->
                actions.updateSettings { it.copy(blendSec = x.toDouble()) }
            }
        }
        SwitchRow("Hard cuts on beat", v.hardCutsOnBeat) { on -> actions.updateSettings { it.copy(hardCutsOnBeat = on) } }
        SwitchRow("Single tap: next preset", v.singleTapNext) { on -> actions.updateSettings { it.copy(singleTapNext = on) } }
    }
    item(key = "playback-scope") {
        Text("Play from")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(v.scope == Scope.AllEnabled, { actions.updateSettings { it.copy(scope = Scope.AllEnabled) } }, { Text("All enabled") })
            FilterChip(v.scope == Scope.Favorites, { actions.updateSettings { it.copy(scope = Scope.Favorites) } }, { Text("Favorites") })
            val scope = v.scope
            FilterChip(
                selected = scope is Scope.PackOnly,
                enabled = current != null || scope is Scope.PackOnly,
                onClick = { current?.let { r -> actions.updateSettings { it.copy(scope = Scope.PackOnly(r.packId)) } } },
                label = { Text("This pack") },
            )
            FilterChip(
                selected = scope is Scope.Folder,
                enabled = current != null || scope is Scope.Folder,
                onClick = { current?.let { r -> actions.updateSettings { it.copy(scope = Scope.Folder(r.packId, r.folder)) } } },
                label = { Text("This folder") },
            )
        }
    }
}
