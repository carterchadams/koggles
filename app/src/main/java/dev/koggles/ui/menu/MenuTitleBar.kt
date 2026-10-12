package dev.koggles.ui.menu

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The menu's always-visible title bar; the "X" is the visible way to close the menu (besides Back). */
@Composable
fun MenuTitleBar(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp).testTag("menu-title-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Koggles Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        // Material icons are not a dependency: the glyph stands in for Icons.Default.Close.
        IconButton(onClick = onClose, modifier = Modifier.testTag("menu-close").semantics { contentDescription = "Close settings" }) {
            Text("✕", style = MaterialTheme.typography.titleLarge)
        }
    }
}
