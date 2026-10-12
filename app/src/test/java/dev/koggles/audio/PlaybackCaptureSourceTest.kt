package dev.koggles.audio

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackCaptureSourceTest {

    private class Recording : AudioSourceListener {
        val events = mutableListOf<String>()
        override fun onStarted() { events += "started" }
        override fun onFailed(reason: FailReason) { events += "failed:$reason" }
        override fun onProjectionStopped() { events += "projectionStopped" }
    }

    private val throwing = object : CaptureServiceLauncher {
        override fun start(consent: ConsentData, generation: Long) = throw IllegalStateException("not allowed to start foreground service")
        override fun stop() = Unit
    }

    /** Android can refuse a foreground-service launch; that must become a recoverable audio error, not a crash. */
    @Test fun launchFailure_reportsCaptureStartFailed_andUnregisters() {
        val listener = Recording()
        val gen = 777L
        PlaybackCaptureSource(ConsentData(-1, Intent()), gen, throwing).start(listener)
        assertEquals(listOf("failed:CaptureStartFailed"), listener.events)
        var delivered = false
        CaptureBus.deliver(gen) { delivered = true }
        assertFalse("a failed start must leave no listener registered", delivered)
    }
}
