package dev.koggles

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import kotlin.math.abs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    private val CALIBRATE = "Configure spacing with volume buttons"

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
        AppGraph.renderCommands.setCalibration(false)
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

    /**
     * The texts of the list's composed children, top to bottom by layout position (a lazy list's child
     * order is composition order), skipping nodes without text (a switch shares its row's top edge).
     */
    private fun listTextsByPosition(): List<String> =
        compose.onNodeWithTag("menu-list").onChildren().fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            .filter { it.isNotEmpty() }

    /** The author (2026-10-13): Goggles mode is the top-most option; licences sit at the very bottom, below Audio. */
    @Test fun menuOrder_gogglesModeFirst_licensesLast() {
        openMenu()
        val top = listTextsByPosition()
        assertEquals("first section expected Display", "Display", top.getOrNull(0))
        assertEquals("first option expected Goggles mode", "Goggles mode", top.getOrNull(1))
        scrollTo(hasText("About & licenses"))
        val bottom = listTextsByPosition()
        assertEquals("last item expected About & licenses: $bottom", "About & licenses", bottom.lastOrNull())
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

    @Test fun titleBar_staysVisibleWhenScrolled_openedFromGogglesMode() {
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

    // ---- orientation (the author, 2026-10-13: the menu is portrait whenever it is open) ----

    private fun requested() = compose.activity.requestedOrientation
    private fun orientation() = compose.activity.resources.configuration.orientation
    private fun awaitRequested(expected: Int, what: String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && requested() != expected) Thread.sleep(50)
        assertEquals("$what: requestedOrientation", expected, requested())
    }
    private fun awaitOrientation(expected: Int, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && orientation() != expected) Thread.sleep(50)
        assertEquals("$what: configuration.orientation", expected, orientation())
        compose.waitForIdle()
    }
    private fun gogglesOn() {
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } }
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.effectiveGoggles }
        awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE, "goggles on")
    }
    private fun gogglesOff() {
        runBlocking { AppGraph.settings.update { it.copy(goggles = false) } }
        compose.waitUntil(5_000) { requested() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun menuOpen_locksPortrait_normalMode() {
        openMenu()
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "open in normal mode")
        awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT, "open in normal mode")
        compose.runOnUiThread { AppGraph.menu.close() }
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "closed in normal mode")
    }

    @Test fun menuOpen_locksPortrait_gogglesMode_andRestoresLandscape() {
        gogglesOn()
        openMenu()
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "open in goggles mode")
        awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT, "open in goggles mode")
        assertTrue(AppGraph.renderCommands.state.value.menuPreview)
        assertFalse(AppGraph.renderCommands.state.value.effectiveGoggles)
        compose.runOnUiThread { AppGraph.menu.close() }
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, "closed in goggles mode")
        awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE, "closed in goggles mode")
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.effectiveGoggles }
        gogglesOff()
    }

    /** Round 1 B2: a pause/resume with the menu open (back from the QR scanner) must not rotate it back to landscape. */
    @Test fun menuOpen_gogglesMode_survivesPauseResume() {
        gogglesOn()
        openMenu()
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "open in goggles mode")
        awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT, "open in goggles mode")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "after pause/resume")
        awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT, "after pause/resume")
        assertTrue(AppGraph.renderCommands.state.value.menuPreview)
        assertFalse(AppGraph.renderCommands.state.value.effectiveGoggles)
        compose.runOnUiThread { AppGraph.menu.close() }
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.effectiveGoggles }
        gogglesOff()
    }

    /** Round 1 B2: goggles switched on inside the open menu, then closed at once: the final state is landscape goggles. */
    @Test fun menuOpen_toggleGoggles_thenClose_landsInLandscape() {
        openMenu()
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "open in normal mode")
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } } // what the Goggles mode switch commits
        compose.runOnUiThread { AppGraph.menu.close() }
        awaitRequested(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, "closed after toggling goggles")
        awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE, "closed after toggling goggles")
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.effectiveGoggles }
        gogglesOff()
    }

    /** The author removed interstitials (2026-10-13): no such row anywhere in the list. */
    @Test fun menu_hasNoInterstitialRow() {
        openMenu()
        val found = runCatching { scrollTo(hasText("Transition interstitials")) }.isSuccess
        assertFalse("row present: Transition interstitials", found)
    }

    // ---- Wide view (the author, 2026-10-13): three views, a lens spacing per view ----

    private fun lensSpacingLabel(): String =
        compose.onNode(hasText("Lens spacing", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    /** The chips pick the view and the slider shows and edits that view's own spacing. */
    @Test fun wideViewChips_switchTheSpacingSlider() {
        // Set by hand (manual flags), else the computed defaults replace these values for the new normal spacing.
        runBlocking {
            AppGraph.settings.update {
                it.copy(wideView = 0, lensOffsetMm = 1f, lensOffsetWideMm = 22f, lensOffsetWideWindowMm = 8.5f, wideSpacingManual = true, wideWindowSpacingManual = true)
            }
        }
        openMenu()
        // Goggles mode switched on inside the open menu: the menu stays portrait, so no rotation (injected touches
        // after a rotation land outside the stale window and fail).
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } }
        compose.waitForIdle()
        // "Off" also names a preset-duration chip: match inside the wide-view row.
        fun pick(chip: String, mode: Int, label: String) {
            val node = hasText(chip) and hasAnyAncestor(hasTestTag("wide-view"))
            scrollTo(node)
            compose.onNode(node).performClick()
            compose.waitUntil(3_000) { runBlocking { AppGraph.settings.values.first().wideView } == mode }
            compose.waitForIdle()
            assertEquals(label, lensSpacingLabel())
        }
        scrollTo(hasText("Wide view"))
        assertEquals("Lens spacing: +1.0 mm", lensSpacingLabel())
        pick("Wide", 1, "Lens spacing: +22.0 mm")
        pick("Wide + window", 2, "Lens spacing: +8.5 mm")
        pick("Off", 0, "Lens spacing: +1.0 mm")
        compose.runOnUiThread { AppGraph.menu.close() }
        gogglesOff()
    }

    /** Spacing calibration (the author, 2026-10-13): the button lives under Goggles mode only. */
    @Test fun calibrateButton_presentOnlyWithGogglesOn() {
        openMenu()
        scrollTo(hasText("Goggles mode"))
        compose.onAllNodesWithText(CALIBRATE).assertCountEquals(0)
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } } // inside the open menu: no rotation
        compose.waitForIdle()
        scrollTo(hasText(CALIBRATE))
        compose.onNodeWithText(CALIBRATE).assertIsDisplayed()
    }

    /** Pressing it closes the menu and starts the calibration render. */
    @Test fun calibrateButton_closesMenu_andStartsCalibration() {
        openMenu()
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } }
        compose.waitForIdle()
        scrollTo(hasText(CALIBRATE))
        compose.onNodeWithText(CALIBRATE).performClick()
        compose.waitUntil(3_000) { !AppGraph.menu.open.value }
        assertEquals(true, AppGraph.renderCommands.state.value.calibration)
        // Goggles off ends it (the setting is the one writer of goggles mode).
        gogglesOff()
        compose.waitUntil(3_000) { !AppGraph.renderCommands.state.value.calibration }
    }

    /** The spacing slider spans -10..40 mm (wide view on a wide phone needs about +28). */
    @Test fun lensSpacingSlider_spansMinus10To40() {
        openMenu()
        runBlocking { AppGraph.settings.update { it.copy(goggles = true) } } // inside the open menu: no rotation
        compose.waitForIdle()
        scrollTo(hasText("Lens spacing", substring = true))
        val spacingSlider = SemanticsMatcher("the Lens spacing slider") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == -10f..40f
        }
        compose.onNode(spacingSlider).assertExists()
        compose.runOnUiThread { AppGraph.menu.close() }
        gogglesOff()
    }

    /** Double tap only opens the menu, never closes it, and the scrim passes nothing to the visualization. */
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
            // Tap the rows where their label is: the row's centre can land on the row's own button (the folder's
            // "Play this folder") when the label wraps, as it does on the Samsung (probed 2026-10-13).
            scrollTo(hasTestTag("pack:${ref.packId}"))
            compose.onNodeWithTag("pack:${ref.packId}").performTouchInput { click(Offset(width * 0.1f, centerY)) }
            scrollTo(hasTestTag("folder:${ref.packId}/${ref.folder}"))
            compose.onNodeWithTag("folder:${ref.packId}/${ref.folder}").performTouchInput { click(Offset(width * 0.1f, centerY)) }
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

    /** The licences copy is user-visible text fixed by the developer: it must match exactly. */
    @Test fun licensesScreen_describesBundledContentExactly() {
        openMenu()
        scrollTo(hasText("About & licenses"))
        compose.onNodeWithText("About & licenses").performClick()
        compose.onNodeWithText(
            "Koggles is built on projectM (LGPL 2.1) and the Cardboard SDK (Apache 2.0), and bundles " +
                "a selection of projectM's classic presets and the Milkdrop texture pack.",
        ).assertIsDisplayed()
    }

    /** H7 (human, 2026-10-12): the presets ship with the projectM team's public-domain statement. */
    @Test fun licensesScreen_includesThePresetsPublicDomainStatement() {
        openMenu()
        scrollTo(hasText("About & licenses"))
        compose.onNodeWithText("About & licenses").performClick()
        compose.onNodeWithText("koggles-starter-pack-presets.md", substring = true).performClick()
        compose.onNodeWithText("it is safe to assume them to be in the public domain", substring = true).assertExists()
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

    /** Counts pixels within ±12 per channel of [argb] in [bmp]. */
    private fun near(bmp: Bitmap, argb: Int) = (0 until bmp.width).sumOf { x -> (0 until bmp.height).count { y ->
        val p = bmp.getPixel(x, y)
        abs(AColor.red(p) - AColor.red(argb)) <= 12 && abs(AColor.green(p) - AColor.green(argb)) <= 12 && abs(AColor.blue(p) - AColor.blue(argb)) <= 12 } }

    /** Brand-images M6: the panel is black whatever the system theme. */
    @Test fun menu_panelIsBlack() {
        openMenu()
        val bmp = compose.onNodeWithTag("menu-list").captureToImage().asAndroidBitmap()
        val inset = (12 * compose.density.density).toInt() // past the panel's antialiased 1 dp border at any density
        assertEquals("panel background", 0xFF000000.toInt(), bmp.getPixel(inset, inset)) // inside the 16 dp content padding
    }

    /** A checked switch's track is the branding cyan, i.e. Material widgets took the theme. */
    @Test fun menu_widgetsAreNeon() {
        openMenu()
        // Scrolled into view first: on a shorter screen (the SM-S731U1, 384x832 dp) the switches sit below the fold
        // and a clipped node captures as an empty bitmap.
        val bmp = compose.onAllNodes(isToggleable() and isOn()).onFirst().performScrollTo().captureToImage().asAndroidBitmap()
        val cyan = (0 until bmp.width).any { x -> (0 until bmp.height).any { y ->
            val p = bmp.getPixel(x, y); AColor.red(p) < 80 && AColor.green(p) > 200 && AColor.blue(p) > 200 } }
        assertTrue("no cyan pixel in a checked switch", cyan)
    }

    /** A selected FilterChip is painted secondaryContainer (deep magenta), not Material's default. */
    @Test fun menu_selectedChipIsDeepMagenta() {
        openMenu()
        val bmp = compose.onAllNodes(isSelectable() and isSelected()).onFirst().performScrollTo().captureToImage().asAndroidBitmap()
        assertTrue("no secondaryContainer pixels in a selected chip", near(bmp, 0xFF3A0A33.toInt()) > 50)
    }

    /** A slider's inactive track is secondaryContainer and its active track is primary (cyan). */
    @Test fun menu_sliderTracksAreNeon() {
        openMenu()
        scrollTo(hasText("Blend", substring = true)) // the label reads "Blend: 3.0 s"
        // The Blend slider, by its range (1..10 s): the section's labels and sliders are flat siblings, so a sibling
        // match would also catch another slider; the Lens spacing slider is -10..10 mm.
        val blendSlider = SemanticsMatcher("the Blend slider") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == 1f..10f
        }
        val bmp = compose.onNode(blendSlider).performScrollTo().captureToImage().asAndroidBitmap()
        assertTrue("no cyan active track", near(bmp, 0xFF00E5FF.toInt()) > 20)
        assertTrue("no deep-magenta inactive track", near(bmp, 0xFF3A0A33.toInt()) > 20)
    }

    /** An unchecked switch's track is surfaceContainerHighest (near black), not Material's grey. */
    @Test fun menu_uncheckedSwitchTrackIsDark() {
        openMenu()
        val bmp = compose.onAllNodes(isToggleable() and isOff()).onFirst().performScrollTo().captureToImage().asAndroidBitmap()
        assertTrue("no surfaceContainerHighest pixels in an unchecked switch", near(bmp, 0xFF1A1A24.toInt()) > 50)
    }
}
