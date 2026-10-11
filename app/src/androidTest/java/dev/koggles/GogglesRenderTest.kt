package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.koggles.render.LensState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GogglesRenderTest {
    private val commands get() = AppGraph.renderCommands

    @After
    fun reset() {
        commands.setGoggles(false)
        commands.setLens(LensState())
    }

    @Test
    fun gogglesOn_rendersWithoutGlError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            commands.setGoggles(true)
            assertFramesClean(scenario, 60)
            assertTrue(NativeBridge.rendererGogglesActive(commands.attachedHandleForTest(scenario)))
        }
    }

    @Test
    fun lensChanges_rebuildWithoutGlError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            commands.setGoggles(true)
            for (offset in listOf(-10f, 0f, 10f)) {
                for (distortion in listOf(true, false)) {
                    commands.setLens(LensState(offsetMm = offset, distortion = distortion))
                    assertFramesClean(scenario, 10)
                }
            }
        }
    }

    @Test
    fun toggleGoggles_20Times_noGlError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(20) { i ->
                commands.setGoggles(i % 2 == 0)
                assertFramesClean(scenario, 3)
            }
        }
    }

    /** Waits for [frames] real frames and asserts each reported GL_NO_ERROR. */
    private fun assertFramesClean(scenario: ActivityScenario<MainActivity>, frames: Int) {
        val bad = AtomicInteger(0)
        val lastError = AtomicInteger(0)
        val drawn = CountDownLatch(frames + 1) // +1: first frame may predate the command
        var first = true
        scenario.onActivity { activity ->
            checkNotNull(activity.visualizerView).onFrameDrawn = { error ->
                if (!first && error != 0) { bad.incrementAndGet(); lastError.set(error) }
                first = false
                drawn.countDown()
            }
        }
        assertTrue("frames drawn", drawn.await(30, SECONDS))
        scenario.onActivity { it.visualizerView?.onFrameDrawn = null }
        assertEquals("GL errors (last 0x${Integer.toHexString(lastError.get())})", 0, bad.get())
    }

    private fun dev.koggles.render.RenderCommands.attachedHandleForTest(scenario: ActivityScenario<MainActivity>): Long {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (attachedHandle == 0L && System.nanoTime() < deadline) Thread.sleep(20)
        return attachedHandle
    }
}
