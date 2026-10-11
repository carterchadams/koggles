package dev.koggles.debug

import android.view.View
import dev.koggles.AppGraph
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.Source

/** Debug only: long-press the visualization to switch between Standard and High quality. */
object DebugSourceToggle {
    fun install(view: View) {
        view.isLongClickable = true
        view.setOnLongClickListener {
            val audio = AppGraph.audio
            val next = if (audio.snapshot.value.source == Source.Standard) Source.HighQuality else Source.Standard
            audio.dispatch(AudioEvent.SelectSource(next))
            true
        }
    }
}
