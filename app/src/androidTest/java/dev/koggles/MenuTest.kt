package dev.koggles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import dev.koggles.playback.NowPlaying
import dev.koggles.playback.PlaybackControls
import dev.koggles.presets.PresetRef
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MenuTest {

    class FakePlayback : PlaybackControls {
        val nexts = AtomicInteger()
        val skips = AtomicInteger()
        override val nowPlaying = MutableStateFlow(NowPlaying())
        override fun next() { nexts.incrementAndGet() }
        override fun previous() = Unit
        override fun random() = Unit
        override fun select(key: String) = Unit
        override suspend fun skip() {
            nowPlaying.value.ref?.let { AppGraph.library.setSkipped(it.key, true) }
            skips.incrementAndGet()
        }
        override suspend fun toggleFavorite() = Unit
        override suspend fun setLocked(on: Boolean) = Unit
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val menu get() = compose.onNodeWithTag("menu")

    /** The explainer is an opaque overlay (touches never reach the visualization): accept it. */
    @Before fun passExplainer() {
        if (AppGraph.audio.snapshot.value.state == AudioState.ShowingExplainer) {
            compose.runOnUiThread { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
        }
        compose.waitUntil(5_000) { AppGraph.audio.snapshot.value.state != AudioState.ShowingExplainer }
        compose.waitForIdle()
    }

    @After fun closeMenu() {
        compose.runOnUiThread { AppGraph.menu.close() }
        runBlocking { AppGraph.settings.update { it.copy(singleTapNext = true, goggles = false) } }
        fake.nexts.set(0); fake.skips.set(0)
    }

    private fun openMenu() {
        compose.onNodeWithTag("visualizer-gestures").performTouchInput { doubleClick() }
        compose.waitUntil(3_000) { AppGraph.menu.open.value }
        compose.waitForIdle()
    }

    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNodeWithTag("menu-list").performScrollToNode(matcher)
    }

    @Test fun doubleTap_opensMenu_withoutAdvancing() {
        openMenu()
        menu.assertIsDisplayed()
        Thread.sleep(600) // past the double-tap timeout: no single tap may fire
        assertEquals(0, fake.nexts.get())
    }

    @Test fun menuOpen_tapsGoToMenuOnly() {
        openMenu()
        menu.performTouchInput { click(center) }
        Thread.sleep(800)
        assertEquals(0, fake.nexts.get())
        assertTrue(AppGraph.menu.open.value)
    }

    @Test fun singleTap_advances_whenEnabled() {
        compose.onNodeWithTag("visualizer-gestures").performTouchInput { click() }
        compose.waitUntil(3_000) { fake.nexts.get() == 1 }
        assertFalse(AppGraph.menu.open.value)
    }

    @Test fun singleTap_disabled_doesNothing() {
        runBlocking { AppGraph.settings.update { it.copy(singleTapNext = false) } }
        compose.waitForIdle()
        compose.onNodeWithTag("visualizer-gestures").performTouchInput { click() }
        Thread.sleep(800)
        assertEquals(0, fake.nexts.get())
    }

    @Test fun menuOpen_forcesSingleViewPreview() {
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } }
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.effectiveGoggles }
        // Goggles mode rotates to landscape: tap only once the rotation and re-layout are done.
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        openMenu()
        assertTrue(AppGraph.renderCommands.state.value.menuPreview)
        assertFalse(AppGraph.renderCommands.state.value.effectiveGoggles)
        compose.runOnUiThread { AppGraph.menu.close() }
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.effectiveGoggles }
        // Leave portrait for the next test.
        runBlocking { AppGraph.settings.update { it.copy(goggles = false) } }
        // Spec: normal mode follows the device, so a real phone lying in landscape stays there;
        // wait for the app to release the lock, not for a physical rotation.
        compose.waitUntil(5_000) {
            compose.activity.requestedOrientation ==
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test fun closeButton_closesMenu() {
        openMenu()
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.waitUntil(3_000) { !AppGraph.menu.open.value }
        menu.assertDoesNotExist()
    }

    private fun assertTitleBarVisibleAfterScroll() {
        openMenu()
        scrollTo(hasText(HQ_CAVEAT, substring = true))
        compose.onNodeWithText("Koggles Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close settings").assertIsDisplayed()
    }

    @Test fun titleBar_staysVisibleWhenScrolled() = assertTitleBarVisibleAfterScroll()

    @Test fun titleBar_staysVisibleWhenScrolled_landscape() {
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        assertTitleBarVisibleAfterScroll()
        compose.runOnUiThread { AppGraph.menu.close() }
        runBlocking { AppGraph.settings.update { it.copy(goggles = false) } }
        // Spec: normal mode follows the device, so a real phone lying in landscape stays there;
        // wait for the app to release the lock, not for a physical rotation.
        compose.waitUntil(5_000) {
            compose.activity.requestedOrientation ==
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** D1: double tap no longer closes the menu, and the scrim passes nothing to the visualization. */
    @Test fun scrimDoubleTap_doesNotClose() {
        openMenu()
        menu.performTouchInput { doubleClick(Offset(4f, centerY)) }
        Thread.sleep(800)
        assertTrue(AppGraph.menu.open.value)
        menu.performTouchInput { click(Offset(4f, centerY)) }
        Thread.sleep(800)
        assertEquals(0, fake.nexts.get())
        assertTrue(AppGraph.menu.open.value)
    }

    @Test fun back_closesMenu() {
        openMenu()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(3_000) { !AppGraph.menu.open.value }
        menu.assertDoesNotExist()
    }

    @Test fun hqRow_showsExactCaveat() {
        openMenu()
        val caveat = HQ_CAVEAT
        scrollTo(hasText(caveat, substring = true))
        compose.onNodeWithText(caveat, substring = true).assertIsDisplayed()
    }

    @Test fun skipButton_dimsRowAndAdvances() {
        val ref = firstBundledRef()
        fake.nowPlaying.value = NowPlaying(ref = ref)
        try {
            openMenu()
            scrollTo(hasTestTag("pack:${ref.packId}"))
            compose.onNodeWithTag("pack:${ref.packId}").performClick()
            scrollTo(hasTestTag("folder:${ref.packId}/${ref.folder}"))
            compose.onNodeWithTag("folder:${ref.packId}/${ref.folder}").performClick()
            scrollTo(hasContentDescription("Skip ${ref.name}"))
            compose.onNodeWithContentDescription("Skip ${ref.name}").performClick()
            compose.waitUntil(3_000) { fake.skips.get() == 1 }
            scrollTo(hasContentDescription("Un-skip ${ref.name}"))
            compose.onNodeWithContentDescription("Un-skip ${ref.name}").assertIsDisplayed()
        } finally {
            runBlocking { AppGraph.library.setSkipped(ref.key, false) }
            fake.nowPlaying.value = NowPlaying()
        }
    }

    @Test fun licensesScreen_listsProjectMAndCardboard() {
        openMenu()
        scrollTo(hasText("About & licenses"))
        compose.onNodeWithText("About & licenses").performClick()
        compose.onNodeWithTag("licenses").assertIsDisplayed()
        compose.onAllNodesWithText("projectM", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("cardboard", substring = true, ignoreCase = true).onFirst().assertExists()
    }

    private fun firstBundledRef(): PresetRef = runBlocking {
        withTimeout(20_000) { AppGraph.libraryRepo.state.first { it.bundledReady } }
            .catalog.packs.first { it.id.startsWith("bundled:") }
            .presets.first { !it.isTransition }
    }

    companion object {
        private val fake = FakePlayback()
        private const val HQ_CAVEAT = "Higher fidelity; some apps block it and it asks again after locking"

        @BeforeClass @JvmStatic fun setUpClass() {
            val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO").close()
            // No first-run folder prompt: these tests open the menu themselves.
            runBlocking { AppGraph.settings.update { it.copy(folderPrompted = true, explainerSeen = true) } }
            AppGraph.overrideForTest(playback = fake)
        }

        @AfterClass @JvmStatic fun tearDownClass() {
            AppGraph.overrideForTest(playback = null)
        }
    }
}
