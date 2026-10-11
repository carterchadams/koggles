package dev.koggles

/** Debug-only behaviour, kept out of main code (D13). None remains; the hooks stay for later use. */
object DebugHooks {
    fun install(@Suppress("UNUSED_PARAMETER") activity: MainActivity) {}

    fun onKeyEvent(@Suppress("UNUSED_PARAMETER") activity: MainActivity, @Suppress("UNUSED_PARAMETER") event: android.view.KeyEvent): Boolean = false

    /** GL thread. */
    fun onRendererReady(@Suppress("UNUSED_PARAMETER") handle: Long) {}
}
