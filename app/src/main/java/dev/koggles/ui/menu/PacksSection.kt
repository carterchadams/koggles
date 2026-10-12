package dev.koggles.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.koggles.presets.Catalog
import dev.koggles.presets.Pack
import dev.koggles.settings.Scope

/** Expansion state of the pack → folder → preset tree (snapshot state: reads recompose). */
class TreeExpansion {
    private val open = mutableStateMapOf<String, Boolean>()
    fun isOpen(id: String) = open[id] == true
    fun toggle(id: String) { open[id] = !isOpen(id) }
}

private fun folderLabel(folder: String) = folder.ifEmpty { "(top level)" }

internal fun LazyListScope.packsSection(
    catalog: Catalog,
    disabled: Set<String>,
    favorites: Set<String>,
    skipped: Set<String>,
    currentKey: String?,
    expanded: TreeExpansion,
    actions: MenuActions,
) {
    item(key = "packs-header") { SectionHeader("Packs") }
    for (pack in catalog.packs) {
        val packKey = "pack:${pack.id}"
        item(key = packKey) {
            PackRow(pack, pack.id !in disabled, expanded.isOpen(packKey), actions) { expanded.toggle(packKey) }
        }
        if (!expanded.isOpen(packKey)) continue
        val byFolder = pack.presets.groupBy { it.folder }
        for ((folder, presets) in byFolder) {
            val folderId = "${pack.id}/$folder"
            item(key = "folder:$folderId") {
                Row(
                    Modifier.fillMaxWidth().testTag("folder:$folderId")
                        .clickable { expanded.toggle("folder:$folderId") }
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (expanded.isOpen("folder:$folderId")) "▾ " else "▸ ") + folderLabel(folder) + " (${presets.size})",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        actions.updateSettings { it.copy(scope = Scope.Folder(pack.id, folder)) }
                    }) { Text("Play this folder") }
                }
            }
            if (!expanded.isOpen("folder:$folderId")) continue
            for (ref in presets) {
                item(key = "preset:${ref.key}") {
                    val isSkipped = ref.key in skipped
                    Row(
                        Modifier.fillMaxWidth().testTag("preset:${ref.key}").padding(start = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ref.name,
                            Modifier.weight(1f).alpha(if (isSkipped) 0.4f else 1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        GlyphButton("▶", "Play ${ref.name}") { actions.playback.select(ref.key) }
                        GlyphButton(if (ref.key in favorites) "★" else "☆", "Favorite ${ref.name}") {
                            actions.launch { actions.library.toggleFavorite(ref.key) }
                        }
                        if (isSkipped) {
                            GlyphButton("↺", "Un-skip ${ref.name}") {
                                actions.launch { actions.library.setSkipped(ref.key, false) }
                            }
                        } else {
                            GlyphButton("⊘", "Skip ${ref.name}") {
                                actions.launch {
                                    // Skipping what is playing also moves on from it.
                                    if (ref.key == currentKey) actions.playback.skip()
                                    else actions.library.setSkipped(ref.key, true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PackRow(pack: Pack, enabled: Boolean, open: Boolean, actions: MenuActions, onToggleOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("pack:${pack.id}").clickable(onClick = onToggleOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((if (open) "▾ " else "▸ ") + "${pack.name} (${pack.presets.size})", Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { on -> actions.launch { actions.library.setPackEnabled(pack.id, on) } })
    }
}
