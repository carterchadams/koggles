package dev.koggles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioSnapshot
import dev.koggles.audio.AudioState
import dev.koggles.audio.FailReason
import kotlinx.coroutines.runBlocking
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

/** The list's own reported scroll position; screen dumps proved unreliable for measuring scroll. */
fun ComposeTestRule.scrollPosition(tag: String): Float =
    onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

/**
 * A slow (1 s) upward swipe of half the node's height, the drag a finger makes: from [start]
 * (node-local) if given, otherwise over the middle half of the node.
 */
fun SemanticsNodeInteraction.slowSwipeUp(start: Offset? = null) = performTouchInput {
    val from = start ?: Offset(centerX, centerY + height / 4f)
    swipe(from, Offset(from.x, maxOf(0f, from.y - height / 2f)), durationMillis = 1_000)
}

/**
 * Slow drags must scroll every overlay list from any start point (a modifier that consumed every
 * pointer change used to cancel slow drags before touch slop), and no overlay may let a tap or
 * double tap through to the visualization.
 */
@RunWith(AndroidJUnit4::class)
class OverlayScrollTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private var restore: AudioSnapshot? = null

    @Before fun passExplainer() {
        if (AppGraph.audio.snapshot.value.state == AudioState.ShowingExplainer) {
            compose.runOnUiThread { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
        }
        compose.waitUntil(5_000) { AppGraph.audio.snapshot.value.state != AudioState.ShowingExplainer }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        compose.runOnUiThread { AppGraph.menu.close() }
        restore?.let { before ->
            // Back to a clean start: Idle, then a stop/start runs begin() (which replaces any source).
            compose.runOnUiThread { AppGraph.audio.resetForTest(before.copy(state = AudioState.Idle)) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        fake.nexts.set(0)
    }

    private fun openMenu() {
        compose.runOnUiThread { AppGraph.menu.show() }
        compose.waitUntil(3_000) { AppGraph.menu.open.value }
        compose.waitForIdle()
    }

    private fun openLicenses() {
        openMenu()
        compose.onNodeWithTag("menu-list").performScrollToNode(hasText("About & licenses"))
        compose.onNodeWithText("About & licenses").performClick()
        compose.onNodeWithTag("licenses").assertIsDisplayed()
    }

    private fun assertScrolls(tag: String, start: Offset? = null) {
        val before = compose.scrollPosition(tag)
        compose.onNodeWithTag(tag).slowSwipeUp(start)
        compose.waitForIdle()
        val after = compose.scrollPosition(tag)
        assertTrue("$tag did not scroll on a slow swipe: $before -> $after", after > before)
    }

    /** Taps, then double-taps, at [at] (root coordinates); neither may reach the visualization. */
    private fun assertNoLeak(at: Offset, menuOpen: Boolean) {
        val root = compose.onNodeWithTag("visualizer-gestures")
        root.performTouchInput { click(at) }
        Thread.sleep(800)
        assertEquals("tap reached the visualization", 0, fake.nexts.get())
        assertEquals("tap changed the menu state", menuOpen, AppGraph.menu.open.value)
        root.performTouchInput { doubleClick(at) }
        Thread.sleep(800)
        assertEquals("double tap reached the visualization", 0, fake.nexts.get())
        assertEquals("double tap changed the menu state", menuOpen, AppGraph.menu.open.value)
    }

    private fun showAudioOverlay(state: AudioState, visibleText: String) {
        restore = AppGraph.audio.snapshot.value
        compose.runOnUiThread {
            AppGraph.audio.resetForTest(
                AudioSnapshot(state = state, activityStarted = true, hasPermission = true),
            )
        }
        compose.onNodeWithText(visibleText).assertIsDisplayed()
    }

    @Test fun menu_slowSwipe_scrolls() {
        openMenu()
        assertScrolls("menu-list")
    }

    @Test fun menu_slowSwipe_fromChip_scrolls() {
        openMenu()
        val list = compose.onNodeWithTag("menu-list").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithText("30 s").fetchSemanticsNode().boundsInRoot
        assertScrolls("menu-list", chip.center - list.topLeft)
    }

    @Test fun licenses_slowSwipe_scrolls() {
        openLicenses()
        compose.onNodeWithText("projectM-LGPL-2.1.txt", substring = true).performClick()
        compose.waitForIdle()
        assertScrolls("licenses-list")
    }

    @Test fun menuPanel_tapAndDoubleTap_doNotReachVisualizer() {
        openMenu()
        // The list's 16 dp side padding: inside the panel, on no control.
        val list = compose.onNodeWithTag("menu-list").fetchSemanticsNode().boundsInRoot
        val padding = with(compose.density) { 6.dp.toPx() }
        assertNoLeak(Offset(list.left + padding, list.center.y), menuOpen = true)
    }

    @Test fun licenses_tapAndDoubleTap_doNotReachVisualizer() {
        openLicenses()
        val title = compose.onNode(hasText("About & licenses") and hasAnyAncestor(hasTestTag("licenses")))
            .fetchSemanticsNode().boundsInRoot
        assertNoLeak(title.center, menuOpen = true)
        compose.onNodeWithTag("licenses").assertIsDisplayed()
    }

    @Test fun explainer_tapAndDoubleTap_doNotReachVisualizer() {
        showAudioOverlay(AudioState.ShowingExplainer, "Continue")
        assertNoLeak(Offset(20f, 20f), menuOpen = false)
        assertFalse(AppGraph.menu.open.value)
    }

    @Test fun errorOverlay_tapAndDoubleTap_doNotReachVisualizer() {
        showAudioOverlay(AudioState.Failed(FailReason.CaptureStartFailed), "Audio capture couldn't start.")
        assertNoLeak(Offset(20f, 20f), menuOpen = false)
    }

    companion object {
        private val fake = MenuTest.FakePlayback()

        @BeforeClass @JvmStatic fun setUpClass() {
            val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO").close()
            runBlocking { AppGraph.settings.update { it.copy(folderPrompted = true, explainerSeen = true) } }
            AppGraph.overrideForTest(playback = fake)
        }

        @AfterClass @JvmStatic fun tearDownClass() {
            AppGraph.overrideForTest(playback = null)
        }
    }
}
