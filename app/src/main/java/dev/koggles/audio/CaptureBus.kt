package dev.koggles.audio

/**
 * Same-process bridge from [CaptureService] to the [PlaybackCaptureSource] that started it,
 * keyed by source generation so callbacks from a superseded capture are dropped (F3).
 */
object CaptureBus {
    private val listeners = HashMap<Long, AudioSourceListener>()

    @Synchronized
    fun register(generation: Long, listener: AudioSourceListener) {
        listeners[generation] = listener
    }

    @Synchronized
    fun unregister(generation: Long) {
        listeners.remove(generation)
    }

    /** Calls [block] on the listener registered for [generation]; drops it if there is none. */
    fun deliver(generation: Long, block: AudioSourceListener.() -> Unit) {
        val listener = synchronized(this) { listeners[generation] } ?: return
        listener.block()
    }
}
