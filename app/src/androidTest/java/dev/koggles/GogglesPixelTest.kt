package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
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
        commands.setCalibration(false)
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

    /**
     * Spacing calibration (the author, 2026-10-13): two bullseyes replace the visualizer, one per eye, and the volume
     * keys move them together or apart. Measured on the display: in each half the lit pixels form a ring (dark at
     * its centroid, lit around it); the two centroids sit about the modelled inter-lens distance apart; +5 mm of
     * spacing moves them about 5 mm further apart; and at the solved default spacing each wide view's bullseye sits
     * where the normal view's does (the fusing criterion the defaults are built on), within a millimetre.
     */
    @Test fun calibration_showsBullseyes_thatTheSpacingMovesApart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("audio active") {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active
            }
            runBlocking { AppGraph.settings.update { it.copy(goggles = true, distortion = true, wideView = 0, lensOffsetMm = 0f) } }
            waitFor("renderer attached") { commands.attachedHandle != 0L }
            waitFor("goggles active") { NativeBridge.rendererGogglesActive(commands.attachedHandle) }
            commands.setCalibration(true)
            val pxPerMm = main.targetContext.resources.displayMetrics.xdpi / 25.4f
            // Past the rotation and the first calibration frames: wait for a landscape capture with lit pixels.
            waitFor("the bullseyes on screen") { shot().let { it.width > it.height && litCount(it) > 50 } }
            val (w, h) = run { val b = shot(); b.width to b.height }
            // Two consecutive captures that agree: the lens rebuild after a settings change is not instantaneous.
            fun centroids(what: String): Pair<Pair<Double, Double>, Pair<Double, Double>> {
                var last: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    Thread.sleep(300)
                    val shot = shot()
                    val left = ring(shot, 0, shot.width / 2, what + " left")
                    val right = ring(shot, shot.width / 2, shot.width, what + " right")
                    val now = left to right
                    val prev = last
                    if (prev != null && abs(prev.first.first - left.first) < 1.0 && abs(prev.second.first - right.first) < 1.0 || System.currentTimeMillis() > deadline) {
                        dump(shot, what)
                        android.util.Log.i("GogglesPixelTest", "$what: left=$left right=$right (${shot.width}x${shot.height})")
                        return now
                    }
                    last = now
                }
            }
            val off0 = centroids("Off +0")
            val apart0 = off0.second.first - off0.first.first
            val expected0 = 60f * pxPerMm
            assertTrue("bullseyes ${apart0 / pxPerMm} mm apart, expected about 60", abs(apart0 - expected0) <= expected0 * 0.1)

            runBlocking { AppGraph.settings.update { it.copy(lensOffsetMm = 5f) } }
            val off5 = centroids("Off +5")
            val apart5 = off5.second.first - off5.first.first
            assertTrue("+5 mm moved the bullseyes ${(apart5 - apart0) / pxPerMm} mm apart, expected about 5", abs((apart5 - apart0) - 5f * pxPerMm) <= 5f * pxPerMm * 0.25)

            // Each wide view at its solved default: the same bullseye positions as the normal view at 0. Then +5 mm
            // more, measured and logged only: in the wide views the field-of-view clamp moves with the spacing, so
            // the bullseyes need not move apart (a finding for the author, not an assertion).
            runBlocking { AppGraph.settings.update { it.copy(lensOffsetMm = 0f) } }
            fun centre(offset: Float, mode: Int) = NativeBridge.lensFrameCentreX(maxOf(w, h), minOf(w, h), offset, mode)
            for (mode in 1..2) {
                val solved = dev.koggles.render.WideSpacingDefaults.solve(0f, mode, ::centre)
                runBlocking {
                    AppGraph.settings.update {
                        if (mode == 1) it.copy(wideView = 1, lensOffsetWideMm = solved, wideSpacingManual = true)
                        else it.copy(wideView = 2, lensOffsetWideWindowMm = solved, wideWindowSpacingManual = true)
                    }
                }
                val wide = centroids("mode $mode at $solved")
                val dl = abs(wide.first.first - off0.first.first) / pxPerMm
                val dr = abs(wide.second.first - off0.second.first) / pxPerMm
                assertTrue("mode $mode at the default: bullseyes $dl / $dr mm from the normal view's", dl <= 1.0 && dr <= 1.0)
                runBlocking {
                    AppGraph.settings.update { if (mode == 1) it.copy(lensOffsetWideMm = solved + 5f) else it.copy(lensOffsetWideWindowMm = solved + 5f) }
                }
                val moved = centroids("mode $mode at ${solved + 5f}")
                android.util.Log.i("GogglesPixelTest", "mode $mode: +5 mm moved the bullseyes ${((moved.second.first - moved.first.first) - (wide.second.first - wide.first.first)) / pxPerMm} mm apart")
            }
            runBlocking { AppGraph.settings.update { it.copy(wideSpacingManual = false, wideWindowSpacingManual = false, wideDefaultsFor = null) } }
        }
    }

    private fun shot(): android.graphics.Bitmap = checkNotNull(main.uiAutomation.takeScreenshot()) { "screenshot" }

    private fun litCount(b: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) if (lumaArgb(b.getPixel(x, y)) > 128) n++
        return n
    }

    /**
     * The bullseye in columns [x0, x1): the centre of its innermost ring. The lit centroid is only the first guess:
     * in a wide view the spacing offset moves the modelled distortion axis away from the bullseye, so the outer rings
     * are no longer concentric with it. From the guess, the first lit pixel in each direction is the inner ring; its
     * crossings' midpoints are the centre (iterated). Asserts the centre is dark and lit pixels surround it.
     */
    private fun ring(b: android.graphics.Bitmap, x0: Int, x1: Int, what: String): Pair<Double, Double> {
        var n = 0L; var sx = 0.0; var sy = 0.0
        val step = 2
        for (y in 0 until b.height step step) for (x in x0 until x1 step step) {
            if (lumaArgb(b.getPixel(x, y)) > 128) { n++; sx += x; sy += y }
        }
        assertTrue("$what: no lit pixels", n > 50)
        var cx = sx / n; var cy = sy / n
        val reach = (x1 - x0) / 4
        fun firstLit(ix: Int, iy: Int, dx: Int, dy: Int): Int? = (1..reach).firstOrNull { r ->
            val x = ix + dx * r; val y = iy + dy * r
            x in x0 until x1 && y in 0 until b.height && lumaArgb(b.getPixel(x, y)) > 128
        }
        repeat(3) {
            val ix = cx.toInt(); val iy = cy.toInt()
            assertTrue("$what: ($ix,$iy) is lit (${lumaArgb(b.getPixel(ix, iy))}), expected the dark centre", lumaArgb(b.getPixel(ix, iy)) < 40)
            val l = firstLit(ix, iy, -1, 0); val r = firstLit(ix, iy, 1, 0)
            val u = firstLit(ix, iy, 0, -1); val d = firstLit(ix, iy, 0, 1)
            assertTrue("$what: no ring around ($ix,$iy)", l != null && r != null && u != null && d != null)
            cx = ix + (r!! - l!!) / 2.0
            cy = iy + (d!! - u!!) / 2.0
        }
        return cx to cy
    }

    /** A failing capture, saved where Gradle collects test output (additionalTestOutputDir). */
    private fun dump(b: android.graphics.Bitmap, what: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val f = java.io.File(dir, "calibration-${what.replace(Regex("[^A-Za-z0-9]+"), "_")}.png")
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("GogglesPixelTest", "dumped $f")
    }

    private fun lumaArgb(argb: Int): Double {
        val r = (argb ushr 16) and 0xff
        val g = (argb ushr 8) and 0xff
        val b = argb and 0xff
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

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
