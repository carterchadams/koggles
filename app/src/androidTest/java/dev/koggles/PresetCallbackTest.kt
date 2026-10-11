package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetCallbackTest {
    private val events = LinkedBlockingQueue<String>()
    private var previous: NativeListener? = null

    @Before fun listen() {
        previous = NativeBridge.listener
        NativeBridge.listener = object : NativeListener {
            override fun onSwitchRequested(hardCut: Boolean) = Unit
            override fun onPresetFailed(requestId: Long, message: String) { events += "failed:$requestId" }
            override fun onPresetLoaded(requestId: Long) { events += "loaded:$requestId" }
        }
    }

    @After fun restore() { NativeBridge.listener = previous }

    private fun nextId() = (AppGraph.renderCommands.state.value.preset?.requestId ?: 0L) + 1_000

    private fun awaitEvent(prefix: String): String? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val e = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (e.startsWith(prefix)) return e
        }
        return null
    }

    private val okData get() = AppGraph.app.assets.open("g1/ok.milk").bufferedReader().use { it.readText() }

    @Test fun okPreset_reportsLoaded() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val id = nextId()
            AppGraph.renderCommands.loadPreset(okData, id, false, emptyList())
            assertEquals("loaded:$id", awaitEvent("loaded:$id"))
        }
    }

    @Test fun failureDetectedOnFirstFrame_reportsFailed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val id = nextId()
            // Over projectM's 1 MiB preset limit: a real load failure (shader errors never report; G1 P2).
            val oversized = okData + "\n" + "// pad\n".repeat(200_000)
            AppGraph.renderCommands.loadPreset(oversized, id, false, emptyList())
            assertNotNull(awaitEvent("failed:$id"))
            assertNull("a failed load must not also report loaded", events.firstOrNull { it == "loaded:$id" })
        }
    }

    @Test fun idle_reportsLoaded() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val id = nextId()
            AppGraph.renderCommands.loadIdle(id)
            assertEquals("loaded:$id", awaitEvent("loaded:$id"))
        }
    }
}
