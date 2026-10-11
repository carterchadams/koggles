package dev.koggles.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.koggles.audio.AudioSnapshot
import dev.koggles.playback.NowPlaying
import dev.koggles.presets.LibraryState
import dev.koggles.settings.SettingsValues
import dev.koggles.ui.menu.MenuActions
import dev.koggles.ui.menu.TreeExpansion
import dev.koggles.ui.menu.audioSection
import dev.koggles.ui.menu.displaySection
import dev.koggles.ui.menu.librarySection
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
 * Spec §4 menu: a flat panel over a dimmed single preview. Touches never reach the visualization;
 * a double tap on the scrim, or Back, closes it.
 */
@Composable
fun MenuOverlay(model: MenuModel, actions: MenuActions, highlightChooseFolder: Boolean, onClose: () -> Unit) {
    var licensesOpen by remember { mutableStateOf(false) }
    BackHandler { if (licensesOpen) licensesOpen = false else onClose() }
    Box(
        Modifier.fillMaxSize().testTag("menu")
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onClose() }) },
    ) {
        Surface(
            modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(12.dp)
                .widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight()
                .consumeAllTouches(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
        ) {
            MenuList(model, actions, highlightChooseFolder) { licensesOpen = true }
        }
        if (licensesOpen) LicensesScreen(onClose = { licensesOpen = false })
    }
}

@Composable
private fun MenuList(model: MenuModel, actions: MenuActions, highlightChooseFolder: Boolean, onOpenLicenses: () -> Unit) {
    val expanded = remember { TreeExpansion() }
    val current = model.now.ref
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag("menu-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // First run: the Library section leads, so "Choose folder" is the first thing seen.
        if (highlightChooseFolder) librarySection(model.library, true, onOpenLicenses, actions)
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
        displaySection(model.settings, actions)
        if (!highlightChooseFolder) librarySection(model.library, false, onOpenLicenses, actions)
        audioSection(model.audio, model.silence, actions)
    }
}
