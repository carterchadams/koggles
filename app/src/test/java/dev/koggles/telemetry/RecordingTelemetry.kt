package dev.koggles.telemetry

/** The fake every test uses: records what reached it. */
class RecordingTelemetry : Telemetry {
    val events = mutableListOf<Event>()
    override fun track(event: Event) { events += event }
}
