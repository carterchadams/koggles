package dev.koggles.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.koggles.audio.AudioSnapshot
import dev.koggles.playback.NowPlaying
import dev.koggles.presets.LibraryState
import dev.koggles.settings.SettingsValues
import dev.koggles.ui.menu.MenuActions
import dev.koggles.ui.menu.MenuTitleBar
import dev.koggles.ui.menu.TreeExpansion
import dev.koggles.ui.menu.audioSection
import dev.koggles.ui.menu.displaySection
import dev.koggles.ui.menu.librarySection
import dev.koggles.ui.menu.licensesItem
import dev.koggles.ui.menu.nowPlayingSection
import dev.koggles.ui.menu.packsSection
import dev.koggles.ui.menu.playbackSection

/** Everything the menu shows, collected by the caller. */
data class MenuModel(
    val now: NowPlaying,
    val settings: SettingsValues,
    val library: LibraryState,
    val disabled: Set<String>,
    val favorites: Set<String>,
    val skipped: Set<String>,
    val audio: AudioSnapshot,
    val silence: Boolean,
)

/**
 * The menu: a flat panel over a dimmed single preview. Touches never reach the visualization;
 * the title bar's X or Back closes it. Double tap only opens it, so an accidental double tap
 * can't dismiss settings.
 */
@Composable
fun MenuOverlay(model: MenuModel, actions: MenuActions, highlightChooseFolder: Boolean, onClose: () -> Unit) {
    var licensesOpen by remember { mutableStateOf(false) }
    BackHandler { if (licensesOpen) licensesOpen = false else onClose() }
    Box(
        Modifier.fillMaxSize().testTag("menu")
            .background(Color.Black.copy(alpha = 0.55f))
            .blockTouchesBelow(),
    ) {
        Surface(
            modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(12.dp)
                .widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight()
                .blockTouchesBelow(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp, // a no-op with KogglesColors' black surfaceTint; harmless if the tint changes
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), // the neon edge of the black panel
        ) {
            Column {
                MenuTitleBar(onClose)
                MenuList(model, actions, highlightChooseFolder, Modifier.weight(1f)) { licensesOpen = true }
            }
        }
        if (licensesOpen) LicensesScreen(onClose = { licensesOpen = false })
    }
}

@Composable
private fun MenuList(
    model: MenuModel,
    actions: MenuActions,
    highlightChooseFolder: Boolean,
    modifier: Modifier,
    onOpenLicenses: () -> Unit,
) {
    val expanded = remember { TreeExpansion() }
    val current = model.now.ref
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxWidth().testTag("menu-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Order fixed by the author (2026-10-13): Goggles mode is the top-most option on every run, the
        // first run included (its highlighted "Choose folder" stays inside Library), and "About &
        // licenses" is the very last item, below Audio.
        displaySection(model.settings, actions)
        nowPlayingSection(
            model.now,
            current?.let { model.library.catalog.pack(it.packId)?.name },
            model.settings.locked,
            current?.key in model.favorites,
            actions,
        )
        playbackSection(model.settings, current, actions)
        packsSection(
            model.library.catalog, model.disabled, model.favorites, model.skipped, current?.key,
            expanded, actions,
        )
        librarySection(model.library, highlightChooseFolder, actions)
        audioSection(model.audio, model.silence, actions)
        licensesItem(onOpenLicenses)
    }
}
