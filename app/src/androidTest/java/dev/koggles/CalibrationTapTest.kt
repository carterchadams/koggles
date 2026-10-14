package dev.koggles

import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
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

/**
 * Ending the spacing calibration from inside the mount (the author, 2026-10-14): a double tap ends it
 * and leaves the menu closed (a Cardboard trigger is a tap); a single tap while calibrating does nothing,
 * because the screen is easily touched while the phone is being inserted. Back still ends it
 * (CalibrationKeysTest).
 */
@RunWith(AndroidJUnit4::class)
class CalibrationTapTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val gestures get() = compose.onNodeWithTag("visualizer-gestures")
    private val commands get() = AppGraph.renderCommands

    /** The explainer is an opaque overlay (touches never reach the visualization): accept it. */
    @Before fun setUp() {
        if (AppGraph.audio.snapshot.value.state == AudioState.ShowingExplainer) {
            compose.runOnUiThread { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
        }
        compose.waitUntil(5_000) { AppGraph.audio.snapshot.value.state != AudioState.ShowingExplainer }
        compose.runOnUiThread { AppGraph.menu.close() }
        runBlocking { AppGraph.settings.update { it.copy(goggles = true, singleTapNext = true) } }
        // The goggles setting has reached the renderer and the landscape layout has settled before any tap.
        compose.waitUntil(3_000) { commands.state.value.effectiveGoggles }
        compose.waitForIdle()
        commands.setCalibration(true)
        compose.waitUntil(3_000) { commands.state.value.calibration }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        commands.setCalibration(false)
        compose.runOnUiThread { AppGraph.menu.close() }
        runBlocking { AppGraph.settings.update { it.copy(goggles = false, singleTapNext = true) } }
        fake.nexts.set(0)
    }

    @Test fun singleTap_whileCalibrating_doesNothing() {
        gestures.performTouchInput { click() }
        Thread.sleep(800) // past the double-tap timeout: the single tap has been confirmed by now
        assertEquals(0, fake.nexts.get())
        assertTrue("calibration ended on a single tap", commands.state.value.calibration)
        assertFalse("the menu opened on a single tap", AppGraph.menu.open.value)
    }

    @Test fun singleTap_whileCalibrating_doesNothing_evenWithSingleTapNextOff() {
        runBlocking { AppGraph.settings.update { it.copy(singleTapNext = false) } }
        compose.waitForIdle()
        gestures.performTouchInput { click() }
        Thread.sleep(800)
        assertEquals(0, fake.nexts.get())
        assertTrue("calibration ended on a single tap", commands.state.value.calibration)
    }

    @Test fun doubleTap_whileCalibrating_endsIt_andLeavesTheMenuClosed() {
        gestures.performTouchInput { doubleClick() }
        compose.waitUntil(3_000) { !commands.state.value.calibration }
        Thread.sleep(600) // no single tap may fire after the double tap
        assertFalse("the menu opened on the double tap", AppGraph.menu.open.value)
        assertEquals(0, fake.nexts.get())
    }

    @Test fun doubleTap_afterCalibrationEnded_opensTheMenu() {
        commands.setCalibration(false)
        compose.waitUntil(3_000) { !commands.state.value.calibration }
        compose.waitForIdle()
        gestures.performTouchInput { doubleClick() }
        compose.waitUntil(3_000) { AppGraph.menu.open.value }
    }

    companion object {
        private val fake = MenuTest.FakePlayback()

        @BeforeClass @JvmStatic fun setUpClass() {
            val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO").close()
            // No first-run folder prompt and no explainer: the tests tap the visualization directly.
            runBlocking { AppGraph.settings.update { it.copy(folderPrompted = true, explainerSeen = true) } }
            AppGraph.overrideForTest(playback = fake)
        }

        @AfterClass @JvmStatic fun tearDownClass() {
            AppGraph.overrideForTest(playback = null)
        }
    }
}
