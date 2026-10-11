package dev.koggles

/** Debug-only behaviour, kept out of main code (D13). */
object DebugHooks {
    fun install(activity: MainActivity) {
        activity.visualizerView?.let { dev.koggles.debug.DebugSourceToggle.install(it) }
    }

    /** Debug lens keys (Task 11): see [dev.koggles.debug.DebugLensKeys]. */
    fun onKeyEvent(activity: MainActivity, event: android.view.KeyEvent): Boolean =
        dev.koggles.debug.DebugLensKeys.onKeyEvent(activity, event)

    /** GL thread. No debug behaviour since Task 19 (playback owns preset loading). */
    fun onRendererReady(@Suppress("UNUSED_PARAMETER") handle: Long) {}
}
