package dev.koggles

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.koggles.playback.NowPlaying
import dev.koggles.ui.menu.MenuActions
import dev.koggles.ui.menu.nowPlayingSection
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingLayoutTest {
    @get:Rule val compose = createComposeRule()

    /** A 320 dp phone: the panel's padding leaves ~264 dp for the six controls. */
    @Test fun allSixControls_fitAt320dp() {
        val actions = MenuActions(
            MenuTest.FakePlayback(), AppGraph.library, AppGraph.settings, MainScope(), {}, {}, {}, {}, {},
        )
        val ref = AppGraph.libraryRepo.state.value.catalog.packs.firstOrNull()?.presets?.firstOrNull()
        compose.setContent {
            LazyColumn(Modifier.width(320.dp - 56.dp)) {
                nowPlayingSection(NowPlaying(ref = ref), "Pack", locked = false, favorite = false, actions = actions)
            }
        }
        for (d in listOf("Previous", "Next", "Random", "Lock", "Favorite", "Skip")) {
            val node = compose.onNodeWithContentDescription(d).assertIsDisplayed()
            val w = node.getBoundsInRoot().let { it.right - it.left }
            assertTrue("$d is $w wide", w >= 40.dp)
        }
    }
}
