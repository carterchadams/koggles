package dev.koggles

/** Release builds have no debug behaviour. */
object DebugHooks {
    fun install(activity: MainActivity) {}
    fun onRendererReady(handle: Long) {}
}
