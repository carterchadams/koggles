package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Blueprint Task 21: end-to-end smoke over the real app with [FakeAudio] driving the audio. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val commands get() = AppGraph.renderCommands
    private val main = InstrumentationRegistry.getInstrumentation()

    companion object {
        /** No system permission dialog may cover the activity (it pauses rendering). */
        @org.junit.BeforeClass @JvmStatic fun grantAudio() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.executeShellCommand(
                "pm grant ${inst.targetContext.packageName} android.permission.RECORD_AUDIO",
            ).close()
        }
    }

    @Before fun setUp() {
        FakeAudio.install()
        // Frame tests must not race an auto-advance (as in LifecycleStressTest).
        runBlocking { AppGraph.settings.update { it.copy(locked = true, goggles = false) } }
    }

    @After fun tearDown() {
        runBlocking { AppGraph.settings.update { it.copy(locked = false, goggles = false) } }
        FakeAudio.uninstall()
    }

    private fun <T> waitFor(what: String, timeoutMs: Long = 20_000, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            value()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun awaitActiveAudio() {
        waitFor("audio past the explainer") {
            val s = AppGraph.audio.snapshot.value.state
            if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
            if (s == AudioState.Active) true else null
        }
    }

    /**
     * Counts frames projectM actually rendered (native frame-stat deltas, read on the GL thread in
     * VisualizerView.onFrameDrawn) until [frames], failing if any frame in the window reported a GL
     * error. Reading the native stats from the test thread would race the renderer.
     */
    private fun assertFramesWithoutGlError(scenario: ActivityScenario<MainActivity>, frames: Int) {
        waitFor("renderer attached") { commands.attachedHandle.takeIf { it != 0L } }
        val drawn = java.util.concurrent.atomic.AtomicInteger(0)
        val firstError = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { activity ->
            checkNotNull(activity.visualizerView).onFrameDrawn = { error ->
                if (error != 0) firstError.compareAndSet(0, error)
                // GL thread: the native counter only advances for frames projectM rendered (not when
                // the renderer has no projectM instance), and reading it here does not race.
                val h = commands.attachedHandle
                val rendered = if (h != 0L) NativeBridge.rendererFrameStats(h)[2].toInt() else 0
                if (drawn.addAndGet(rendered) >= frames) done.countDown()
            }
        }
        try {
            assertTrue("$frames frames drawn (got ${drawn.get()})", done.await(90, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            scenario.onActivity { it.visualizerView?.onFrameDrawn = null }
        }
        assertEquals("first GL error in the measured window", 0, firstError.get())
    }

    @Test fun normalMode_120Frames_noGlError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActiveAudio()
            assertFramesWithoutGlError(scenario, 120)
        }
    }

    @Test fun gogglesMode_120Frames_noGlError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActiveAudio()
            runBlocking { AppGraph.settings.update { it.copy(goggles = true) } }
            val h = waitFor("goggles active") {
                commands.attachedHandle.takeIf { it != 0L && NativeBridge.rendererGogglesActive(it) }
            }
            assertTrue(NativeBridge.rendererGogglesActive(h))
            assertFramesWithoutGlError(scenario, 120)
        }
    }

    @Test fun presetAdvances_onNext() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitActiveAudio()
            val before = waitFor("a preset playing") { AppGraph.playback.nowPlaying.value.ref?.key }
            main.runOnMainSync { AppGraph.playback.next() }
            val deadline = System.currentTimeMillis() + 2_000
            var after = AppGraph.playback.nowPlaying.value.ref?.key
            while (after == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                after = AppGraph.playback.nowPlaying.value.ref?.key
            }
            assertNotEquals("nowPlaying key changes within 2 s", before, after)
        }
    }
}
