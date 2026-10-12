package dev.koggles.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.koggles.presets.LibraryState

internal fun LazyListScope.librarySection(
    s: LibraryState,
    highlightChooseFolder: Boolean,
    onOpenLicenses: () -> Unit,
    actions: MenuActions,
) {
    item(key = "library-header") { SectionHeader("Library") }
    item(key = "library-body") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // First run: the one call to action is a filled button.
            if (highlightChooseFolder) Button(onClick = actions.onChooseFolder) { Text("Choose folder") }
            else OutlinedButton(onClick = actions.onChooseFolder) { Text("Choose folder") }
            OutlinedButton(onClick = actions.onRescan, enabled = s.hasGrant) { Text("Rescan") }
        }
        val user = s.catalog.packs.filter { it.id.startsWith("user:") }
        val bundled = s.catalog.packs.filterNot { it.id.startsWith("user:") }
        Text("Bundled: ${bundled.size} packs, ${bundled.sumOf { it.presets.size }} presets")
        Text(
            if (s.hasGrant) "Your folder: ${user.size} packs, ${user.sumOf { it.presets.size }} presets"
            else "No folder chosen (Documents/Koggles)",
        )
        if (s.userIndexing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Indexing…")
            }
        }
        for (e in s.errors.take(MAX_ERRORS)) Text(e, style = MaterialTheme.typography.bodySmall)
        if (s.errors.size > MAX_ERRORS) Text("…and ${s.errors.size - MAX_ERRORS} more", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onOpenLicenses) { Text("About & licenses") }
    }
}

private const val MAX_ERRORS = 20
