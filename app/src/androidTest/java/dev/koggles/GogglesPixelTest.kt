package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goggles mode must put real pixels in both eyes, with no GL error from the eye copy (on a Samsung
 * phone the eye copy failed under ANGLE and both eyes stayed black, while the emulator's SwiftShader
 * tolerated it). Measured per frame on the GL thread, over many frames (never one screenshot).
 */
@RunWith(AndroidJUnit4::class)
class GogglesPixelTest {
    private val commands get() = AppGraph.renderCommands
    private val main = InstrumentationRegistry.getInstrumentation()

    companion object {
        private const val FRAMES = 180
        private const val MIN_LUMA = 24.0
        /** An outer-edge pixel is black (0.0) when the mesh stops short of it; any content there reads well above this. */
        private const val EDGE_MIN_LUMA = 8.0

        @BeforeClass @JvmStatic fun grantAudio() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.executeShellCommand(
                "pm grant ${inst.targetContext.packageName} android.permission.RECORD_AUDIO",
            ).close()
        }
    }

    @Before fun setUp() {
        FakeAudio.install()
        runBlocking { AppGraph.settings.update { it.copy(locked = true, goggles = false, folderPrompted = true) } }
    }

    @After fun tearDown() {
        runBlocking { AppGraph.settings.update { it.copy(locked = false, goggles = false, distortion = true, wideView = 0) } }
        FakeAudio.uninstall()
    }

    @Test fun gogglesDistortionOn_eyesRenderPixels_noCopyErrors() = assertEyesRender(distortion = true)

    @Test fun gogglesDistortionOff_eyesRenderPixels_noCopyErrors() = assertEyesRender(distortion = false)

    /** Wide view (the author, 2026-10-13): the distortion mesh reaches the screen's outer edges. */
    @Test fun wideView_lightsTheOuterEdges() = assertOuterEdges(wideView = 1, lit = true)

    @Test fun wideViewWithWindow_lightsTheOuterEdges() = assertOuterEdges(wideView = 2, lit = true)

    /** The normal view keeps the profile's field of view: the outer 2 % of each side stays black. */
    @Test fun normalView_leavesTheOuterEdgesDark() = assertOuterEdges(wideView = 0, lit = false)

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ok()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun luma(rgba: Int): Double {
        val r = (rgba ushr 24) and 0xff
        val g = (rgba ushr 16) and 0xff
        val b = (rgba ushr 8) and 0xff
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** Max luma over [FRAMES] frames at 1 % and 99 % of the width, mid-height (the outer edges of the two eyes). */
    private fun assertOuterEdges(wideView: Int, lit: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("audio active") {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active
            }
            val data = AppGraph.app.assets.open("g1/ok.milk").bufferedReader().use { it.readText() }
            val id = (commands.state.value.preset?.requestId ?: 0L) + 1_000L
            commands.loadPreset(data, id, false, emptyList())
            runBlocking { AppGraph.settings.update { it.copy(goggles = true, distortion = true, wideView = wideView) } }
            waitFor("renderer attached") { commands.attachedHandle != 0L }
            val h = commands.attachedHandle
            waitFor("goggles active") { NativeBridge.rendererGogglesActive(h) }
            Thread.sleep(500)
            val maxLeft = AtomicInteger()
            val maxRight = AtomicInteger()
            val maxCentre = AtomicInteger()
            val drawn = CountDownLatch(FRAMES)
            scenario.onActivity { activity ->
                val view = checkNotNull(activity.visualizerView)
                view.onFrameDrawn = { _ ->
                    if (drawn.count > 0) {
                        val w = view.width
                        val y = view.height / 2
                        maxLeft.accumulateAndGet((luma(NativeBridge.glReadPixelRgba(w / 100, y)) * 1000).toInt(), ::maxOf)
                        maxRight.accumulateAndGet((luma(NativeBridge.glReadPixelRgba(w - w / 100, y)) * 1000).toInt(), ::maxOf)
                        maxCentre.accumulateAndGet((luma(NativeBridge.glReadPixelRgba(w / 4, y)) * 1000).toInt(), ::maxOf)
                        drawn.countDown()
                    }
                }
            }
            assertTrue("frames drawn", drawn.await(60, SECONDS))
            scenario.onActivity { it.visualizerView?.onFrameDrawn = null }
            val summary = "wideView=$wideView maxLuma edgeL=${maxLeft.get() / 1000.0} edgeR=${maxRight.get() / 1000.0} centreL=${maxCentre.get() / 1000.0}"
            android.util.Log.i("GogglesPixelTest", summary)
            assertTrue("the eye itself is dark (control): $summary", maxCentre.get() / 1000.0 >= MIN_LUMA)
            if (lit) {
                assertTrue("left outer edge dark: $summary", maxLeft.get() / 1000.0 >= EDGE_MIN_LUMA)
                assertTrue("right outer edge dark: $summary", maxRight.get() / 1000.0 >= EDGE_MIN_LUMA)
            } else {
                assertTrue("left outer edge lit in normal view: $summary", maxLeft.get() / 1000.0 < 2.0)
                assertTrue("right outer edge lit in normal view: $summary", maxRight.get() / 1000.0 < 2.0)
            }
        }
    }

    private fun assertEyesRender(distortion: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("audio active") {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active
            }
            val data = AppGraph.app.assets.open("g1/ok.milk").bufferedReader().use { it.readText() }
            val id = (commands.state.value.preset?.requestId ?: 0L) + 1_000L
            commands.loadPreset(data, id, false, emptyList())
            runBlocking { AppGraph.settings.update { it.copy(goggles = true, distortion = distortion) } }
            waitFor("renderer attached") { commands.attachedHandle != 0L }
            val h = commands.attachedHandle
            waitFor("goggles active") { NativeBridge.rendererGogglesActive(h) }
            Thread.sleep(500) // past the rotation and first goggles frames

            val copyErrorsBefore = NativeBridge.rendererEyeCopyErrors(h)
            val glErrorFrames = AtomicInteger()
            val lastGlError = AtomicInteger()
            val maxLeft = AtomicInteger()  // luma * 1000, as an int
            val maxRight = AtomicInteger()
            val drawn = CountDownLatch(FRAMES)
            scenario.onActivity { activity ->
                val view = checkNotNull(activity.visualizerView)
                view.onFrameDrawn = { error ->
                    if (drawn.count > 0) {
                        if (error != 0) { glErrorFrames.incrementAndGet(); lastGlError.set(error) }
                        val w = view.width
                        val y = view.height / 2
                        val l = (luma(NativeBridge.glReadPixelRgba(w / 4, y)) * 1000).toInt()
                        val r = (luma(NativeBridge.glReadPixelRgba(3 * w / 4, y)) * 1000).toInt()
                        maxLeft.accumulateAndGet(l, ::maxOf)
                        maxRight.accumulateAndGet(r, ::maxOf)
                        drawn.countDown()
                    }
                }
            }
            assertTrue("frames drawn", drawn.await(60, SECONDS))
            scenario.onActivity { it.visualizerView?.onFrameDrawn = null }
            val copyErrors = NativeBridge.rendererEyeCopyErrors(h) - copyErrorsBefore
            val summary = "copyErrors=$copyErrors glErrorFrames=${glErrorFrames.get()} " +
                "(last 0x${Integer.toHexString(lastGlError.get())}) " +
                "maxLuma L=${maxLeft.get() / 1000.0} R=${maxRight.get() / 1000.0}"
            android.util.Log.i("GogglesPixelTest", "distortion=$distortion $summary")
            assertEquals("eye copy errors: $summary", 0, copyErrors)
            assertEquals("frames with GL errors: $summary", 0, glErrorFrames.get())
            assertTrue("left eye dark: $summary", maxLeft.get() / 1000.0 >= MIN_LUMA)
            assertTrue("right eye dark: $summary", maxRight.get() / 1000.0 >= MIN_LUMA)
        }
    }
}
