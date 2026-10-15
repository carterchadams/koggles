package dev.koggles.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent boundary. The gate's own `enabled` state is the authority, flipped synchronously by the UI before
 * anything is persisted (Codex spec review round 2, finding 2): an event tracked right after the flip is dropped,
 * whatever a settings Flow has or has not delivered yet.
 */
class ConsentGateTest {
    private var built = 0
    private val inner = RecordingTelemetry()
    private fun gate(persisted: Boolean) = ConsentGate(persisted) { built++; inner }

    @Test fun offDropsEventsAndNeverBuildsTheVendor() {
        val g = gate(persisted = false)
        g.track(Event.CalibrationFinished)
        assertEquals(0, built)
        assertTrue(inner.events.isEmpty())
        assertFalse(g.enabled.value)
    }

    @Test fun seededOnFromThePersistedSettingPassesEventsThrough() {
        val g = gate(persisted = true)
        g.track(Event.CalibrationFinished)
        assertEquals(listOf<Event>(Event.CalibrationFinished), inner.events)
    }

    @Test fun turningOnBuildsTheVendorOnceAndPasses() {
        val g = gate(persisted = false)
        g.setEnabled(true)
        g.track(Event.ViewerQrScanned)
        g.track(Event.CalibrationFinished)
        assertEquals(1, built)
        assertEquals(2, inner.events.size)
        assertTrue(g.enabled.value)
    }

    @Test fun turningOffStopsTheVeryNextEvent() {
        val g = gate(persisted = true)
        g.track(Event.ViewerQrScanned)
        g.setEnabled(false)
        g.track(Event.CalibrationFinished)
        assertEquals(listOf<Event>(Event.ViewerQrScanned), inner.events)
        assertFalse(g.enabled.value)
    }

    @Test fun turningBackOnReusesTheVendor() {
        val g = gate(persisted = false)
        g.setEnabled(true)
        g.setEnabled(false)
        g.setEnabled(true)
        g.track(Event.CalibrationFinished)
        assertEquals(1, built)
        assertEquals(1, inner.events.size)
    }
}
