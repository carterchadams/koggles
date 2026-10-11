package dev.koggles.audio

import android.content.Context
import android.content.Intent

/** "High quality" source: playback capture, run by [CaptureService]. */
class PlaybackCaptureSource(
    private val context: Context,
    private val consent: ConsentData,
    private val generation: Long,
) : AudioSource {

    override fun start(listener: AudioSourceListener) {
        CaptureBus.register(generation, listener)
        context.startForegroundService(CaptureService.intent(context, consent, generation))
    }

    override fun stop() {
        // Unregister first: the service's teardown fires projection callbacks we must not see.
        CaptureBus.unregister(generation)
        context.stopService(Intent(context, CaptureService::class.java))
    }
}
