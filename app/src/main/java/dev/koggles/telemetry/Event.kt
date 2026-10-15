package dev.koggles.telemetry

import dev.koggles.audio.Source

/**
 * Everything the app can report, and the whole of it. Each event is a name and Int-only properties (the Aptabase
 * SDK documents String and Int custom properties; booleans go as 0/1). No free text, no preset names, no paths, no
 * identifiers. EventTest pins this schema; the README's Privacy section discloses it word for word.
 */
sealed class Event(val name: String, val props: Map<String, Int> = emptyMap()) {
    class AppStarted(source: Source, goggles: Boolean, wideView: Int) :
        Event("app_started", mapOf("source" to source.ordinal, "goggles" to goggles.toProp(), "wide_view" to wideView))

    class SessionEnded(minutes: Int) : Event("session_ended", mapOf("minutes" to minutes))

    class GogglesToggled(on: Boolean) : Event("goggles_toggled", mapOf("on" to on.toProp()))

    class WideViewSet(mode: Int) : Event("wide_view_set", mapOf("mode" to mode))

    data object ViewerQrScanned : Event("viewer_qr_scanned")

    data object CalibrationFinished : Event("calibration_finished")
}

internal fun Boolean.toProp(): Int = if (this) 1 else 0
