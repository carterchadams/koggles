package dev.koggles

/** Renderer events, delivered on the main thread (see [NativeBridge.listener]). */
interface NativeListener {
    fun onSwitchRequested(hardCut: Boolean, requestId: Long)
    fun onPresetFailed(requestId: Long, message: String)
    fun onPresetLoaded(requestId: Long)
}
