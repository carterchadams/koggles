package dev.koggles.telemetry

/** Reports one event. Main thread; never blocks. The vendor adapter does its own I/O off-thread. */
fun interface Telemetry {
    fun track(event: Event)
}

/** Analytics unavailable (no app key in this build): nothing is ever sent. */
object NoopTelemetry : Telemetry {
    override fun track(event: Event) = Unit
}
