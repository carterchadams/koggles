package dev.koggles.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The consent boundary in front of the vendor. Its own [enabled] is the authority, not the persisted setting: the
 * UI calls [setEnabled] first, synchronously on the main thread, and persists afterwards, so an event tracked right
 * after "off" is dropped whatever the settings Flow has delivered yet. If the persist then fails, the gate stays
 * closed for this process and the switch (which reads [enabled]) shows off. [persisted] seeds the state once at
 * startup. The vendor is built on the first consented event and never before, so with the switch off the SDK is
 * never initialised. Main thread only.
 */
class ConsentGate(persisted: Boolean, private val vendor: () -> Telemetry) : Telemetry {
    private val _enabled = MutableStateFlow(persisted)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private var inner: Telemetry? = null

    fun setEnabled(on: Boolean) {
        _enabled.value = on
    }

    override fun track(event: Event) {
        if (!_enabled.value) return
        (inner ?: vendor().also { inner = it }).track(event)
    }
}
