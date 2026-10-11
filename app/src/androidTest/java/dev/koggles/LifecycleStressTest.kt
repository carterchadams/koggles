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
            awaitPresetLoaded()
            repeat(20) {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertFrameOk(scenario)
            }
            repeat(20) {
                scenario.recreate()
                assertFrameOk(scenario)
            }
            repeat(40) { i -> commands.setGoggles(i % 2 == 0) }
            assertFrameOk(scenario)
        }
    }

    @Test
    fun pauseResumeWithoutRecreation_rendererStaysAttached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPresetLoaded()
            val requestId = commands.state.value.preset?.requestId
            repeat(20) {
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertNotEquals(0L, awaitAttached())
                assertFrameOk(scenario)
                assertEquals(requestId, commands.state.value.preset?.requestId)
            }
        }
    }

    @Test
    fun contextLossPath_recreatesAndReplays() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPresetLoaded()
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
            assertFrameOk(scenario)
            Thread.sleep(500) // no further destroys may follow
            assertEquals(1, NativeBridge.rendererDestroyCount() - destroysBefore)
            assertEquals(1L, commands.state.value.preset?.requestId)
        }
    }

    private fun awaitPresetLoaded() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (commands.state.value.preset?.requestId != 1L) {
            assertTrue("debug preset loaded", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }

    private fun awaitAttached(): Long {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (commands.attachedHandle == 0L) {
            assertTrue("renderer attached", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
        return commands.attachedHandle
    }

    /** Waits for the current view's GL thread to run, then checks the last frame's GL error. */
    private fun assertFrameOk(scenario: ActivityScenario<MainActivity>) {
        awaitAttached()
        val glError = AtomicInteger(-1)
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = checkNotNull(activity.visualizerView)
            view.queueEvent {
                NativeBridge.rendererDrawFrame(commands.attachedHandle)
                glError.set(NativeBridge.rendererLastGlError(commands.attachedHandle))
                done.countDown()
            }
        }
        assertTrue("GL thread ran", done.await(10, SECONDS))
        assertEquals(0, glError.get())
        assertEquals(1L, commands.state.value.preset?.requestId)
    }
}
