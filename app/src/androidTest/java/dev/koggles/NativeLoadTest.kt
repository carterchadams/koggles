package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.koggles.render.VisualizerView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLoadTest {
    @Test
    fun libraryLoadsAndRendererCreates() {
        val ready = CountDownLatch(1)
        val handle = AtomicLong(0)
        lateinit var view: VisualizerView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = VisualizerView(activity)
                view.onRendererReady = { h ->
                    handle.set(h)
                    ready.countDown()
                }
                activity.setContentView(view)
            }
            assertTrue("renderer ready", ready.await(10, SECONDS))

            val glError = AtomicInteger(-1)
            val drawn = CountDownLatch(1)
            view.queueEvent {
                NativeBridge.rendererDrawFrame(handle.get())
                glError.set(NativeBridge.rendererLastGlError(handle.get()))
                drawn.countDown()
            }
            assertTrue("frame drawn", drawn.await(10, SECONDS))
            assertEquals(0, glError.get())
        }
    }
}
