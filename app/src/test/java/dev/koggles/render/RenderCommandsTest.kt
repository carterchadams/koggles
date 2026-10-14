package dev.koggles.render

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderCommandsTest {

    private class RecordingPort : RendererPort {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun setTexturePaths(h: Long, paths: List<String>) { calls += "texturePaths($h,${paths.joinToString()})" }
        override fun setTransition(h: Long, t: TransitionState) { calls += "transition($h,${t.blendSec})" }
        override fun setGoggles(h: Long, on: Boolean) { calls += "goggles($h,$on)" }
        override fun setLens(h: Long, lens: LensState) { calls += "lens($h,${lens.offsetMm},${lens.wideView})" }
        override fun setCalibration(h: Long, on: Boolean) { calls += "calibration($h,$on)" }
        override fun load(h: Long, data: String, requestId: Long, smooth: Boolean) { calls += "load($h,$data,$requestId,$smooth)" }
        override fun loadIdle(h: Long, requestId: Long) { calls += "idle($h,$requestId)" }
    }

    private val port = RecordingPort()
    private val commands = RenderCommands(port)

    @Test
    fun commandsBeforeAttach_onlyUpdateState() {
        commands.setGoggles(true)
        commands.setLens(LensState(offsetMm = 1.5f))
        commands.loadPreset("x", 3, true, listOf("/t"))
        commands.drain()
        assertTrue(port.calls.isEmpty())
        assertEquals(true, commands.state.value.goggles)
        assertEquals(PresetLoad(3, "x"), commands.state.value.preset)
        assertEquals(listOf("/t"), commands.state.value.texturePaths)
    }

    @Test
    fun attach_replaysInOrder() {
        commands.setTransition(TransitionState(blendSec = 5.0))
        commands.setGoggles(true)
        commands.setLens(LensState(offsetMm = 2f))
        commands.loadPreset("x", 7, true, listOf("/t"))
        commands.attach(1)
        assertEquals(
            listOf("texturePaths(1,/t)", "transition(1,5.0)", "goggles(1,true)", "lens(1,2.0,0)", "calibration(1,false)", "load(1,x,7,false)"),
            port.calls,
        )
    }

    @Test
    fun attach_replaysIdlePreset() {
        commands.loadIdle(4)
        commands.attach(1)
        assertEquals("idle(1,4)", port.calls.last())
    }

    @Test
    fun attach_dropsQueuedCommandsInFavorOfReplay() {
        commands.attach(1)
        commands.setGoggles(true) // queued, not yet drained
        port.calls.clear()
        commands.attach(2)
        commands.drain()
        assertEquals(1, port.calls.count { it.startsWith("goggles(") })
    }

    @Test
    fun liveCommands_applyOnlyWhenDrained() {
        commands.attach(1)
        port.calls.clear()
        commands.setGoggles(true)
        assertTrue(port.calls.isEmpty())
        commands.drain()
        assertEquals(listOf("goggles(1,true)"), port.calls)
    }

    @Test
    fun menuPreview_forcesSingleView() {
        commands.attach(1)
        port.calls.clear()
        commands.setGoggles(true)
        commands.setMenuPreview(true)
        commands.setMenuPreview(false)
        commands.drain()
        assertEquals(listOf("goggles(1,true)", "goggles(1,false)", "goggles(1,true)"), port.calls)
        assertEquals(true, commands.state.value.effectiveGoggles)
    }

    @Test
    fun attach_returnsPreviousHandleForDisposal() {
        assertEquals(0L, commands.attach(1))
        assertEquals(1L, commands.attach(2))
        assertEquals(2L, commands.attachedHandle)
    }

    @Test
    fun drawWithoutAttach_isNoOp() {
        assertEquals(0L, commands.attachedHandle)
        assertEquals(0L, commands.handleForCurrentThread())
        commands.setTransition(TransitionState())
        commands.drain()
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun nonOwnerThread_seesNoHandleAndCannotDrain() {
        commands.attach(1)
        port.calls.clear()
        commands.setGoggles(true)
        var seen = -1L
        thread {
            seen = commands.handleForCurrentThread()
            commands.drain()
        }.join()
        assertEquals(0L, seen)
        assertTrue(port.calls.isEmpty())
        commands.drain()
        assertEquals(listOf("goggles(1,true)"), port.calls)
    }

    @Test
    fun olderRequestIdIgnored() {
        commands.attach(1)
        port.calls.clear()
        commands.loadPreset("a", 5, true, emptyList())
        commands.loadPreset("b", 4, true, emptyList())
        commands.loadIdle(3)
        commands.drain()
        assertEquals(5L, commands.state.value.preset?.requestId)
        assertEquals(1, port.calls.count { it.startsWith("load(") || it.startsWith("idle(") })
    }

    @Test
    fun concurrentLoads_neverRegress() {
        commands.attach(1)
        port.calls.clear()
        val start = CountDownLatch(1)
        val workers = (0 until 4).map { w ->
            thread {
                start.await()
                for (i in 0 until 500) {
                    val id = (i * 4 + w + 1).toLong()
                    if (id % 2 == 0L) commands.loadPreset("p$id", id, true, emptyList()) else commands.loadIdle(id)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        commands.drain()
        val ids = port.calls.mapNotNull { Regex("""(?:load\(1,[^,]*,|idle\(1,)(\d+)""").find(it)?.groupValues?.get(1)?.toLong() }
        assertEquals(ids.sorted(), ids) // submissions never go backwards
        assertEquals(2000L, commands.state.value.preset?.requestId)
        assertEquals(2000L, ids.last())
    }

    @Test
    fun liveLoad_sendsTexturePathsThenLoad() {
        commands.attach(1)
        port.calls.clear()
        commands.loadPreset("x", 9, true, listOf("/a", "/b"))
        commands.drain()
        assertEquals(listOf("texturePaths(1,/a, /b)", "load(1,x,9,true)"), port.calls)
    }

    @Test
    fun lastAppliedRequestId_tracksExecutedLoadsOnly() {
        commands.loadPreset("a", 3, true, emptyList())
        assertEquals(0L, commands.lastAppliedRequestId) // accepted, not applied
        commands.attach(1)
        assertEquals(3L, commands.lastAppliedRequestId) // replay applied it
        commands.loadIdle(4)
        assertEquals(3L, commands.lastAppliedRequestId)
        commands.drain()
        assertEquals(4L, commands.lastAppliedRequestId)
    }

    /** Wide view rides the lens state to the renderer (the mode decides the field of view and the per-eye window). */
    @Test
    fun setLens_carriesTheWideViewMode() {
        commands.attach(1)
        port.calls.clear()
        commands.setLens(LensState(offsetMm = 15.5f, wideView = 2))
        commands.drain()
        assertEquals(listOf("lens(1,15.5,2)"), port.calls)
        assertEquals(2, commands.state.value.lens.wideView)
    }

    /** Spacing calibration (the author, 2026-10-13): the bullseyes replace the visualizer on the GL thread. */
    @Test
    fun setCalibration_reachesTheRenderer_andIsReplayedOnAttach() {
        commands.attach(1)
        port.calls.clear()
        commands.setCalibration(true)
        assertEquals(true, commands.state.value.calibration)
        commands.drain()
        assertEquals(listOf("calibration(1,true)"), port.calls)
        port.calls.clear()
        commands.attach(2)
        assertEquals(listOf("texturePaths(2,)", "transition(2,3.0)", "goggles(2,false)", "lens(2,0.0,0)", "calibration(2,true)"), port.calls)
    }
}
