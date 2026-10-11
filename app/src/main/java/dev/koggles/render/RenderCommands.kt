package dev.koggles.render

import dev.koggles.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    override fun setTexturePaths(h: Long, paths: List<String>) =
        NativeBridge.rendererSetTextureSearchPaths(h, paths.toTypedArray())
    override fun setTransition(h: Long, t: TransitionState) {} // wired in Task 19
    override fun setGoggles(h: Long, on: Boolean) = NativeBridge.rendererSetGoggles(h, on)
    override fun setLens(h: Long, lens: LensState) =
        NativeBridge.rendererSetLens(h, lens.offsetMm, lens.distortion, xdpi)
    override fun load(h: Long, data: String, requestId: Long, smooth: Boolean) =
        NativeBridge.rendererLoadPresetData(h, data, requestId, smooth)
    override fun loadIdle(h: Long, requestId: Long) = NativeBridge.rendererLoadIdle(h, requestId)
}

/**
 * Process-scoped renderer front end. Commands update [state] atomically and queue a native
 * operation. The queue is drained by the GL thread that owns the attached renderer, at the start
 * of each frame — the only point where its EGL context and surface are guaranteed current.
 * A newly attached renderer gets the whole state replayed and the stale queue discarded.
 */
class RenderCommands(private val native: RendererPort) {

    private val lock = Any()
    private val _state = MutableStateFlow(RenderState())
    val state: StateFlow<RenderState> = _state.asStateFlow()

    private val pending = ArrayDeque<(Long) -> Unit>() // guarded by lock

    /** Attached native renderer, 0 when none. */
    @Volatile
    var attachedHandle: Long = 0L
        private set

    @Volatile
    private var owner: Thread? = null

    /** Request id most recently applied to the native renderer (0 if none). */
    @Volatile
    var lastAppliedRequestId: Long = 0L
        private set

    fun loadPreset(data: String, requestId: Long, smooth: Boolean, texturePaths: List<String>) = mutate {
        if (isSuperseded(it, requestId)) return@mutate null
        it.copy(preset = PresetLoad(requestId, data), texturePaths = texturePaths) to { h: Long ->
            // A newer request accepted since this one was queued supersedes it.
            if (_state.value.preset?.requestId == requestId) {
                native.setTexturePaths(h, texturePaths)
                native.load(h, data, requestId, smooth)
                lastAppliedRequestId = requestId
            }
        }
    }

    fun loadIdle(requestId: Long) = mutate {
        if (isSuperseded(it, requestId)) return@mutate null
        it.copy(preset = PresetLoad(requestId, null)) to { h: Long ->
            if (_state.value.preset?.requestId == requestId) {
                native.loadIdle(h, requestId)
                lastAppliedRequestId = requestId
            }
        }
    }

    fun setGoggles(on: Boolean) = mutate {
        val next = it.copy(goggles = on)
        next to { h: Long -> native.setGoggles(h, next.effectiveGoggles) }
    }

    fun setMenuPreview(on: Boolean) = mutate {
        val next = it.copy(menuPreview = on)
        next to { h: Long -> native.setGoggles(h, next.effectiveGoggles) }
    }

    fun setLens(lens: LensState) = mutate {
        it.copy(lens = lens) to { h: Long -> native.setLens(h, lens) }
    }

    fun setTransition(t: TransitionState) = mutate {
        it.copy(transition = t) to { h: Long -> native.setTransition(h, t) }
    }

    /**
     * GL thread, from `onSurfaceCreated`, with a freshly created renderer. The calling thread
     * becomes the owner. Returns the previously attached handle (0 if none), discards queued
     * operations, and replays the full state into [handle].
     */
    fun attach(handle: Long): Long {
        val snapshot: RenderState
        val previous: Long
        synchronized(lock) {
            previous = attachedHandle
            attachedHandle = handle
            owner = Thread.currentThread()
            pending.clear()
            snapshot = _state.value
        }
        native.setTexturePaths(handle, snapshot.texturePaths)
        native.setTransition(handle, snapshot.transition)
        native.setGoggles(handle, snapshot.effectiveGoggles)
        native.setLens(handle, snapshot.lens)
        snapshot.preset?.let { p ->
            if (p.data == null) native.loadIdle(handle, p.requestId)
            else native.load(handle, p.data, p.requestId, false)
            lastAppliedRequestId = p.requestId
        }
        return previous
    }

    /** The attached handle if the calling thread owns it, else 0. */
    fun handleForCurrentThread(): Long =
        if (owner === Thread.currentThread()) attachedHandle else 0L

    /** Owner GL thread, at the start of a frame: applies queued operations in order. */
    fun drain() {
        val h = handleForCurrentThread()
        if (h == 0L) return
        while (true) {
            val op = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            op(h)
        }
    }

    /** Applies [change] atomically; a non-null result updates state and queues its operation. */
    private inline fun mutate(change: (RenderState) -> Pair<RenderState, (Long) -> Unit>?) {
        synchronized(lock) {
            val (next, op) = change(_state.value) ?: return
            _state.value = next
            if (attachedHandle != 0L) pending.addLast(op)
        }
    }

    private fun isSuperseded(s: RenderState, requestId: Long): Boolean {
        val current = s.preset?.requestId ?: return false
        return requestId < current
    }
}
