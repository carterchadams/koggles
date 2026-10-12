package dev.koggles

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleStressTest {
    private val pushing = AtomicBoolean(false)
    private var pusher: Thread? = null
    private val commands get() = AppGraph.renderCommands

    /**
     * These tests check that one preset survives lifecycle churn. Real playback (Task 19) would
     * auto-advance mid-test, so hold it: lock (stops projectM's timed switches) and wait out any
     * interstitial (its own timer advances it even when locked).
     */
    @Before
    fun holdPlayback() {
        runBlocking { AppGraph.settings.update { it.copy(locked = true) } }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!commands.state.value.transition.locked || AppGraph.playback.nowPlaying.value.interstitial) {
            assertTrue("playback held", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
        Thread.sleep(300) // the controller's own settings collector
    }

    @After
    fun releasePlayback() {
        runBlocking { AppGraph.settings.update { it.copy(locked = false) } }
    }

    @Before
    fun startPcm() {
        pushing.set(true)
        pusher = Thread {
            val frames = 1024
            val buf = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var phase = 0.0
            while (pushing.get()) {
                buf.clear()
                repeat(frames) {
                    val v = sin(phase).toFloat()
                    buf.put(v); buf.put(v)
                    phase += 2 * PI * 1000.0 / 44_100.0
                }
                NativeBridge.pushPcmFloat(buf, frames)
                Thread.sleep(20)
            }
        }.also { it.start() }
    }

    @After
    fun stopPcm() {
        pushing.set(false)
        pusher?.join(2000)
    }

    @Test
    fun churn_noGlErrorsAndPresetKept() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = awaitPresetLoaded()
            repeat(20) {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertFrameOk(scenario, id)
            }
            repeat(20) {
                scenario.recreate()
                assertFrameOk(scenario, id)
            }
            repeat(40) { i -> commands.setGoggles(i % 2 == 0) }
            assertFrameOk(scenario, id)
        }
    }

    @Test
    fun pauseResumeWithoutRecreation_rendererStaysAttached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val requestId = awaitPresetLoaded()
            repeat(20) {
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertNotEquals(0L, awaitAttached())
                assertFrameOk(scenario, requestId)
            }
        }
    }

    @Test
    fun contextLossPath_recreatesAndReplays() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = awaitPresetLoaded()
            awaitAttached()
            val destroysBefore = NativeBridge.rendererDestroyCount()
            scenario.recreate()
            // Handles are native addresses and may be reused after delete, so the new renderer is
            // identified by the old one having been destroyed, not by a different handle value.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (NativeBridge.rendererDestroyCount() == destroysBefore || commands.attachedHandle == 0L) {
                assertTrue("old renderer destroyed and new one attached", System.nanoTime() < deadline)
                Thread.sleep(20)
            }
            assertFrameOk(scenario, id)
            scenario.onActivity { activity ->
                assertEquals("first frame after context loss", 0, activity.visualizerView?.firstFrameGlError)
            }
            Thread.sleep(500) // no further destroys may follow
            assertEquals(1, NativeBridge.rendererDestroyCount() - destroysBefore)
        }
    }

    @Test
    fun loadDuringPause_appliedOnResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = awaitPresetLoaded()
            val data = AppGraph.app.assets.open("g1/ok.milk").bufferedReader().use { it.readText() }
            scenario.moveToState(Lifecycle.State.STARTED) // paused: GL thread has no current context
            commands.loadPreset(data, id + 1, false, emptyList())
            commands.setGoggles(true)
            commands.setGoggles(false)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFrameOk(scenario, id + 1)
        }
    }

    /** RenderCommands is process-scoped, so earlier tests may have advanced the request id. */
    private fun awaitPresetLoaded(): Long {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (commands.state.value.preset == null) {
            assertTrue("a preset loaded", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
        return commands.state.value.preset!!.requestId
    }

    private fun awaitAttached(): Long {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (commands.attachedHandle == 0L) {
            assertTrue("renderer attached", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
        return commands.attachedHandle
    }

    /**
     * Waits for the current view to draw a real frame (after draining queued commands) and checks
     * that frame's GL error, the accepted request, and the request applied to the native renderer.
     */
    private fun assertFrameOk(scenario: ActivityScenario<MainActivity>, expectedRequestId: Long) {
        awaitAttached()
        val glError = AtomicInteger(-1)
        val drawn = CountDownLatch(2) // skip a frame that may have started before the hook was set
        scenario.onActivity { activity ->
            checkNotNull(activity.visualizerView).onFrameDrawn = { error ->
                glError.set(error)
                drawn.countDown()
            }
        }
        assertTrue("real frame drawn", drawn.await(10, SECONDS))
        scenario.onActivity { it.visualizerView?.onFrameDrawn = null }
        assertEquals(0, glError.get())
        assertEquals(expectedRequestId, commands.state.value.preset?.requestId)
        assertEquals(expectedRequestId, commands.lastAppliedRequestId)
    }
}
