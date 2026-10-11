package dev.koggles.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import dev.koggles.playback.NowPlaying

internal fun LazyListScope.nowPlayingSection(
    now: NowPlaying,
    packName: String?,
    locked: Boolean,
    favorite: Boolean,
    actions: MenuActions,
) {
    item(key = "now-header") { SectionHeader("Now playing") }
    item(key = "now-body") {
        val ref = now.ref
        Text(ref?.name ?: "Idle", style = MaterialTheme.typography.bodyLarge)
        if (packName != null) Text(packName, style = MaterialTheme.typography.bodySmall)
        now.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        // Six equal shares of the row: every control stays visible and tappable on narrow phones.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val cell = Modifier.weight(1f)
            GlyphButton("◀", "Previous", cell) { actions.playback.previous() }
            GlyphButton("▶", "Next", cell) { actions.playback.next() }
            GlyphButton("🔀", "Random", cell) { actions.playback.random() }
            GlyphButton(if (locked) "🔒" else "🔓", if (locked) "Unlock" else "Lock", cell) {
                actions.launch { actions.playback.setLocked(!locked) }
            }
            GlyphButton(if (favorite) "★" else "☆", "Favorite", cell, enabled = ref != null) {
                actions.launch { actions.playback.toggleFavorite() }
            }
            GlyphButton("⊘", "Skip", cell, enabled = ref != null) { actions.launch { actions.playback.skip() } }
        }
    }
}
