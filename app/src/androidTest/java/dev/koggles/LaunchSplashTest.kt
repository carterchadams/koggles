package dev.koggles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import dev.koggles.ui.SPLASH_MS
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launch logo fade (brand-images M5). The test clock is frozen before the activity exists, so no frame of
 * the fade runs before the first assertion; the overlay's field is opaque black, so a captured pixel of the
 * logo node is alpha × logo and both ramps can be asserted.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSplashTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    companion object {
        /** As in SmokeTest: a system permission dialog over the activity hides its Compose roots from the test. */
        @BeforeClass @JvmStatic fun grantAudio() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.executeShellCommand(
                "pm grant ${inst.targetContext.packageName} android.permission.RECORD_AUDIO",
            ).close()
        }
    }

    @Before fun launchFrozen() {
        compose.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.mainClock.advanceTimeByFrame() // the first frame: the overlay composes and its animation starts
    }

    @After fun tearDown() {
        compose.runOnUiThread { AppGraph.menu.close() }
        scenario.close()
    }

    /** sRGB luma (not relative luminance): Compose blends in sRGB, so alpha × logo is linear in this measure. */
    private fun luma(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

    /** Peak luma along the logo node's middle row. */
    private fun peak(): Float {
        val bmp = compose.onNodeWithTag("launch-logo").captureToImage().asAndroidBitmap()
        val h = bmp.height
        return (0 until bmp.width).maxOf { x -> luma(Color(bmp.getPixel(x, h / 2))) }
    }

    /** In over 400 ms, held, out by 1600 ms: half-bright mid-ramp both ways, full at the hold, gone at 1700 ms. */
    @Test fun logoFadesInHoldsAndFadesOut() {
        compose.onNodeWithTag("launch-logo").assertExists()
        compose.mainClock.advanceTimeBy(200); val midIn = peak()      // t = 200: alpha ≈ 0.5
        compose.mainClock.advanceTimeBy(600); val hold = peak()       // t = 800: alpha = 1
        compose.mainClock.advanceTimeBy(600); val midOut = peak()     // t = 1400: alpha ≈ 0.5
        assertTrue("hold too dark: $hold", hold > 0.3f)
        assertTrue("fade-in not mid-way at 200 ms: $midIn vs hold $hold", midIn > 0.3f * hold && midIn < 0.7f * hold)
        assertTrue("fade-out not mid-way at 1400 ms: $midOut vs hold $hold", midOut > 0.3f * hold && midOut < 0.7f * hold)
        compose.mainClock.advanceTimeBy(300)                           // t = 1700 > SPLASH_MS
        check(SPLASH_MS == 1600)
        compose.onNodeWithTag("launch-logo").assertDoesNotExist()
    }

    /** A double tap while the logo is still up opens the menu: the overlay passes touches through. */
    @Test fun doubleTapDuringFade_opensMenu() {
        // The explainer covers the gesture layer on a fresh install: accept it, then one frame to recompose.
        if (AppGraph.audio.snapshot.value.state == AudioState.ShowingExplainer) {
            compose.runOnUiThread { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(800)                  // the hold: logo fully visible
        compose.onNodeWithTag("launch-logo").assertExists()
        compose.onNodeWithTag("visualizer-gestures").performTouchInput { doubleClick() }
        compose.waitUntil(3_000) { AppGraph.menu.open.value }
        compose.onNodeWithTag("launch-logo").assertExists() // still mid-fade when the menu opened
    }
}
