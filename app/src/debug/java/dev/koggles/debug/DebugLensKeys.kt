package dev.koggles.debug

import android.view.KeyEvent
import dev.koggles.AppGraph
import dev.koggles.MainActivity

/**
 * Debug only (removed in Task 20): volume up/down = lens offset ±0.5 mm (clamped ±10);
 * long-press volume down = toggle goggles; long-press volume up = scan viewer QR.
 */
object DebugLensKeys {
    fun onKeyEvent(activity: MainActivity, event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!up && !down) return false
        val commands = AppGraph.renderCommands
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) event.startTracking()
            KeyEvent.ACTION_UP -> {
                if (event.isCanceled) return true
                if (event.eventTime - event.downTime >= LONG_PRESS_MS) {
                    if (down) commands.setGoggles(!commands.state.value.goggles) else activity.scanViewerQr()
                } else {
                    val lens = commands.state.value.lens
                    val next = (lens.offsetMm + if (up) 0.5f else -0.5f).coerceIn(-10f, 10f)
                    commands.setLens(lens.copy(offsetMm = next))
                }
            }
        }
        return true
    }

    private const val LONG_PRESS_MS = 600L
}
