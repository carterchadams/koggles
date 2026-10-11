package dev.koggles.ui.menu

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import dev.koggles.settings.SettingsValues

internal fun LazyListScope.displaySection(v: SettingsValues, actions: MenuActions) {
    item(key = "display-header") { SectionHeader("Display") }
    item(key = "display-body") {
        SwitchRow("Goggles mode", v.goggles) { on -> actions.updateSettings { it.copy(goggles = on) } }
        if (v.goggles) {
            OutlinedButton(onClick = actions.onScanQr) { Text("Scan viewer QR") }
            StepSlider("Lens spacing", v.lensOffsetMm, -10f..10f, 0.5f, { "%+.1f mm".format(it) }) { x ->
                actions.updateSettings { it.copy(lensOffsetMm = x) }
            }
            SwitchRow("Distortion correction", v.distortion) { on -> actions.updateSettings { it.copy(distortion = on) } }
        }
    }
}
