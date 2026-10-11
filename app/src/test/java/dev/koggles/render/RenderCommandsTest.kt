package dev.koggles.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderCommandsTest {

    private class RecordingPort : RendererPort {
        val calls = mutableListOf<String>()
        override fun setTexturePaths(h: Long, paths: List<String>) { calls += "texturePaths($h,${paths.joinToString()})" }
        override fun setTransition(h: Long, t: TransitionState) { calls += "transition($h,${t.blendSec})" }
        override fun setGoggles(h: Long, on: Boolean) { calls += "goggles($h,$on)" }
        override fun setLens(h: Long, lens: LensState) { calls += "lens($h,${lens.offsetMm})" }
        override fun load(h: Long, data: String, requestId: Long, smooth: Boolean) { calls += "load($h,$data,$requestId,$smooth)" }
        override fun loadIdle(h: Long, requestId: Long) { calls += "idle($h,$requestId)" }
    }

    private val immediate = object : GlExecutor {
        override fun run(block: () -> Unit) = block()
    }

    private val port = RecordingPort()
    private val commands = RenderCommands(immediate, port)

    @Test
    fun commandsBeforeAttach_onlyUpdateState() {
        commands.setGoggles(true)
        commands.setLens(LensState(offsetMm = 1.5f))
        commands.loadPreset("x", 3, true, listOf("/t"))
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
            listOf("texturePaths(1,/t)", "transition(1,5.0)", "goggles(1,true)", "lens(1,2.0)", "load(1,x,7,false)"),
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
    fun menuPreview_forcesSingleView() {
        commands.attach(1)
        port.calls.clear()
        commands.setGoggles(true)
        commands.setMenuPreview(true)
        commands.setMenuPreview(false)
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
        commands.setTransition(TransitionState())
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun olderRequestIdIgnored() {
        commands.attach(1)
        port.calls.clear()
        commands.loadPreset("a", 5, true, emptyList())
        commands.loadPreset("b", 4, true, emptyList())
        commands.loadIdle(3)
        assertEquals(5L, commands.state.value.preset?.requestId)
        assertEquals(1, port.calls.count { it.startsWith("load(") || it.startsWith("idle(") })
    }

    @Test
    fun liveLoad_sendsTexturePathsThenLoad() {
        commands.attach(1)
        port.calls.clear()
        commands.loadPreset("x", 9, true, listOf("/a", "/b"))
        assertEquals(listOf("texturePaths(1,/a, /b)", "load(1,x,9,true)"), port.calls)
    }
}
