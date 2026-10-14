package dev.koggles

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spacing calibration keys (the author, 2026-10-13): while calibrating, the volume keys step the current view's
 * spacing by 0.5 mm and are consumed; otherwise they pass through to the system. Back ends the calibration.
 */
@RunWith(AndroidJUnit4::class)
class CalibrationKeysTest {
    private val main = InstrumentationRegistry.getInstrumentation()
    private val commands get() = AppGraph.renderCommands

    companion object {
        /** The audio machine reaches Active only with the permission (as MenuTest and GogglesPixelTest grant it). */
        @BeforeClass @JvmStatic fun grantAudio() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.executeShellCommand(
                "pm grant ${inst.targetContext.packageName} android.permission.RECORD_AUDIO",
            ).close()
        }
    }

    @Before fun setUp() {
        FakeAudio.install()
        runBlocking { AppGraph.settings.update { it.copy(goggles = true, wideView = 1, lensOffsetWideMm = 10f, wideSpacingManual = true, folderPrompted = true) } }
    }

    @After fun tearDown() {
        commands.setCalibration(false)
        runBlocking { AppGraph.settings.update { it.copy(goggles = false, wideView = 0) } }
        FakeAudio.uninstall()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ok()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun spacing() = runBlocking { AppGraph.settings.values.first().lensOffsetWideMm }

    private fun press(activity: MainActivity, keyCode: Int): Pair<Boolean, Boolean> {
        var down = false
        var up = false
        main.runOnMainSync {
            down = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            up = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        return down to up
    }

    @Test fun volumeKeys_stepTheSpacing_onlyWhileCalibrating() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("audio active") {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active
            }
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }

            // Not calibrating: the keys are the system's (not consumed), the spacing untouched.
            val (downOff, upOff) = press(activity, KeyEvent.KEYCODE_VOLUME_UP)
            assertFalse("volume up consumed outside calibration", downOff)
            assertFalse("volume up (up) consumed outside calibration", upOff)
            Thread.sleep(300)
            assertEquals(10f, spacing(), 0f)

            commands.setCalibration(true)
            val (downOn, upOn) = press(activity, KeyEvent.KEYCODE_VOLUME_UP)
            assertTrue("volume up consumed while calibrating", downOn)
            assertTrue("volume up (up) consumed while calibrating", upOn)
            waitFor("+0.5 mm") { spacing() == 10.5f }
            press(activity, KeyEvent.KEYCODE_VOLUME_DOWN)
            press(activity, KeyEvent.KEYCODE_VOLUME_DOWN)
            waitFor("-1.0 mm") { spacing() == 9.5f }
            assertTrue("still set by hand", runBlocking { AppGraph.settings.values.first().wideSpacingManual })

            // Back ends the calibration and is consumed.
            val (downBack, upBack) = press(activity, KeyEvent.KEYCODE_BACK)
            assertTrue("back consumed while calibrating", downBack && upBack)
            waitFor("calibration ended") { !commands.state.value.calibration }
            assertEquals(9.5f, spacing(), 0f)
        }
    }

    /**
     * The system Back arrives through the back dispatcher, not as KEYCODE_BACK, on Android 16 with this target
     * (predictive back): it must end the calibration, not leave the app; and outside calibration (menu closed) no
     * callback of ours is armed, so Back keeps its default.
     */
    @Test fun systemBack_endsTheCalibration_andIsOtherwiseUnarmed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("audio active") {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active
            }
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            main.runOnMainSync { AppGraph.menu.close() }
            Thread.sleep(300)
            var armed = true
            main.runOnMainSync { armed = activity.onBackPressedDispatcher.hasEnabledCallbacks() }
            assertFalse("a back callback armed outside calibration", armed)

            commands.setCalibration(true)
            waitFor("back callback armed") {
                var a = false
                main.runOnMainSync { a = activity.onBackPressedDispatcher.hasEnabledCallbacks() }
                a
            }
            main.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            waitFor("calibration ended") { !commands.state.value.calibration }
            Thread.sleep(300)
            assertFalse("the activity finished on Back", activity.isFinishing)
        }
    }

    @Test fun openingTheMenu_endsTheCalibration() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("audio active") {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active
            }
            commands.setCalibration(true)
            main.runOnMainSync { AppGraph.menu.toggle() } // what the double tap does
            waitFor("calibration ended") { !commands.state.value.calibration }
            main.runOnMainSync { AppGraph.menu.close() }
        }
    }
}
