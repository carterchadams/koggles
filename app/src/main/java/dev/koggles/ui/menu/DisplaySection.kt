package dev.koggles.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.koggles.settings.LENS_OFFSET_RANGE
import dev.koggles.settings.SettingsValues
import dev.koggles.settings.effectiveLensOffsetMm
import dev.koggles.settings.withLensOffsetForCurrentMode

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.displaySection(v: SettingsValues, actions: MenuActions) {
    item(key = "display-header") { SectionHeader("Display") }
    item(key = "display-body") {
        SwitchRow("Goggles mode", v.goggles) { on -> actions.updateSettings { it.copy(goggles = on) } }
        if (v.goggles) {
            OutlinedButton(onClick = actions.onScanQr) { Text("Scan viewer QR") }
            // Wide view (the author, 2026-10-13): the field of view opened to the screen edges, and with a
            // window each eye sees its own side of the frame. Each view keeps its own lens spacing.
            Text("Wide view")
            FlowRow(Modifier.testTag("wide-view"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(v.wideView == 0, { actions.updateSettings { it.copy(wideView = 0) } }, { Text("Off") })
                FilterChip(v.wideView == 1, { actions.updateSettings { it.copy(wideView = 1) } }, { Text("Wide") })
                FilterChip(v.wideView == 2, { actions.updateSettings { it.copy(wideView = 2) } }, { Text("Wide + window") })
            }
            StepSlider(
                "Lens spacing", v.effectiveLensOffsetMm(), LENS_OFFSET_RANGE, 0.5f, { "%+.1f mm".format(it) },
            ) { x -> actions.updateSettings { it.withLensOffsetForCurrentMode(x) } }
            SwitchRow("Distortion correction", v.distortion) { on -> actions.updateSettings { it.copy(distortion = on) } }
        }
    }
}
