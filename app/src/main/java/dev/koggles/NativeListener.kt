package dev.koggles

/** Renderer events, delivered on the main thread (see [NativeBridge.listener]). */
interface NativeListener {
    fun onSwitchRequested(hardCut: Boolean)
    fun onPresetFailed(requestId: Long, message: String)
    fun onPresetLoaded(requestId: Long)
}
