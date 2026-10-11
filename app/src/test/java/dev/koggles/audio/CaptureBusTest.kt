package dev.koggles.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureBusTest {

    private class Recorder : AudioSourceListener {
        val events = mutableListOf<String>()
        override fun onStarted() { events += "started" }
        override fun onFailed(reason: FailReason) { events += "failed" }
        override fun onProjectionStopped() { events += "stopped" }
    }

    @After
    fun clear() {
        CaptureBus.unregister(1)
        CaptureBus.unregister(2)
    }

    @Test
    fun deliverToUnregisteredGeneration_dropped() {
        val r = Recorder()
        CaptureBus.register(1, r)
        CaptureBus.deliver(2) { onStarted() }
        assertEquals(emptyList<String>(), r.events)
    }

    @Test
    fun unregisterThenLateDeliver_dropped() {
        val r = Recorder()
        CaptureBus.register(1, r)
        CaptureBus.unregister(1)
        CaptureBus.deliver(1) { onProjectionStopped() }
        assertEquals(emptyList<String>(), r.events)
    }

    @Test
    fun twoGenerations_routeIndependently() {
        val a = Recorder()
        val b = Recorder()
        CaptureBus.register(1, a)
        CaptureBus.register(2, b)
        CaptureBus.deliver(1) { onProjectionStopped() }
        CaptureBus.deliver(2) { onStarted() }
        assertEquals(listOf("stopped"), a.events)
        assertEquals(listOf("started"), b.events)
    }
}
