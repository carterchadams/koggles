package dev.koggles

/** Debug-only behaviour, kept out of main code (D13). */
object DebugHooks {
    private var appContext: android.content.Context? = null

    fun install(activity: MainActivity) {
        appContext = activity.applicationContext
    }

    /** GL thread. G1: autoload the probe preset with requestId 1 (removed in Task 19). */
    fun onRendererReady(handle: Long) {
        val ctx = appContext ?: return
        if (AppGraph.renderCommands.state.value.preset != null) return
        val data = ctx.assets.open("g1/ok.milk").bufferedReader().use { it.readText() }
        AppGraph.renderCommands.loadPreset(data, 1L, false, emptyList())
    }
}
