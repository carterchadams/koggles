package dev.koggles.render

import dev.koggles.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Runs blocks on the GL thread. */
fun interface GlExecutor {
    fun run(block: () -> Unit)
}

/** Seam over the `NativeBridge.renderer*` calls. Every call happens on the GL thread. */
interface RendererPort {
    fun setTexturePaths(h: Long, paths: List<String>)
    fun setTransition(h: Long, t: TransitionState)
    fun setGoggles(h: Long, on: Boolean)
    fun setLens(h: Long, lens: LensState)
    fun load(h: Long, data: String, requestId: Long, smooth: Boolean)
    fun loadIdle(h: Long, requestId: Long)
}

class NativeRendererPort(private val xdpi: Float) : RendererPort {
    override fun setTexturePaths(h: Long, paths: List<String>) {} // wired in Task 12
    override fun setTransition(h: Long, t: TransitionState) {} // wired in Task 19
    override fun setGoggles(h: Long, on: Boolean) {} // wired in Task 11
    override fun setLens(h: Long, lens: LensState) {} // wired in Task 11
    override fun load(h: Long, data: String, requestId: Long, smooth: Boolean) =
        NativeBridge.rendererLoadPresetData(h, data, requestId, smooth)
    override fun loadIdle(h: Long, requestId: Long) = NativeBridge.rendererLoadIdle(h, requestId)
}

/**
 * Process-scoped renderer front end. Commands always update [state]; they reach the native
 * renderer only while one is attached. A newly attached renderer gets the whole state replayed.
 */
class RenderCommands(private val exec: GlExecutor, private val native: RendererPort) {

    private val _state = MutableStateFlow(RenderState())
    val state: StateFlow<RenderState> = _state.asStateFlow()

    /** Native renderer handle, 0 when none. Read and written on the GL thread. */
    @Volatile
    var attachedHandle: Long = 0L
        private set

    fun loadPreset(data: String, requestId: Long, smooth: Boolean, texturePaths: List<String>) {
        if (isSuperseded(requestId)) return
        _state.update { it.copy(preset = PresetLoad(requestId, data), texturePaths = texturePaths) }
        onGl { h ->
            native.setTexturePaths(h, texturePaths)
            native.load(h, data, requestId, smooth)
        }
    }

    fun loadIdle(requestId: Long) {
        if (isSuperseded(requestId)) return
        _state.update { it.copy(preset = PresetLoad(requestId, null)) }
        onGl { h -> native.loadIdle(h, requestId) }
    }

    fun setGoggles(on: Boolean) {
        _state.update { it.copy(goggles = on) }
        val effective = _state.value.effectiveGoggles
        onGl { h -> native.setGoggles(h, effective) }
    }

    fun setMenuPreview(on: Boolean) {
        _state.update { it.copy(menuPreview = on) }
        val effective = _state.value.effectiveGoggles
        onGl { h -> native.setGoggles(h, effective) }
    }

    fun setLens(lens: LensState) {
        _state.update { it.copy(lens = lens) }
        onGl { h -> native.setLens(h, lens) }
    }

    fun setTransition(t: TransitionState) {
        _state.update { it.copy(transition = t) }
        onGl { h -> native.setTransition(h, t) }
    }

    /**
     * GL thread, from `onSurfaceCreated`, with a freshly created renderer. Returns the
     * previously attached handle (0 if none) and replays the full state into [handle].
     */
    fun attach(handle: Long): Long {
        val previous = attachedHandle
        attachedHandle = handle
        val s = _state.value
        native.setTexturePaths(handle, s.texturePaths)
        native.setTransition(handle, s.transition)
        native.setGoggles(handle, s.effectiveGoggles)
        native.setLens(handle, s.lens)
        when (val p = s.preset) {
            null -> Unit
            else -> if (p.data == null) native.loadIdle(handle, p.requestId)
            else native.load(handle, p.data, p.requestId, false)
        }
        return previous
    }

    private fun isSuperseded(requestId: Long): Boolean {
        val current = _state.value.preset?.requestId ?: return false
        return requestId < current
    }

    private fun onGl(block: (Long) -> Unit) {
        if (attachedHandle == 0L) return
        exec.run {
            val h = attachedHandle
            if (h != 0L) block(h)
        }
    }
}
