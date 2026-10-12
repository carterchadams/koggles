package dev.koggles.audio

import android.content.Context
import android.content.Intent

/** Starts and stops the capture foreground service; injectable so a launch failure can be tested. */
interface CaptureServiceLauncher {
    fun start(consent: ConsentData, generation: Long)
    fun stop()
}

class AndroidCaptureServiceLauncher(private val context: Context) : CaptureServiceLauncher {
    override fun start(consent: ConsentData, generation: Long) {
        context.startForegroundService(CaptureService.intent(context, consent, generation))
    }
    override fun stop() {
        context.stopService(Intent(context, CaptureService::class.java))
    }
}

/** "High quality" source: playback capture, run by [CaptureService]. */
class PlaybackCaptureSource(
    private val consent: ConsentData,
    private val generation: Long,
    private val launcher: CaptureServiceLauncher,
) : AudioSource {
    constructor(context: Context, consent: ConsentData, generation: Long) :
        this(consent, generation, AndroidCaptureServiceLauncher(context))

    override fun start(listener: AudioSourceListener) {
        CaptureBus.register(generation, listener)
        try {
            launcher.start(consent, generation)
        } catch (e: RuntimeException) {
            // Android can refuse a foreground-service launch (background restrictions, a revoked
            // projection). The service never ran, so nothing else reports it: fail like a start error.
            CaptureBus.unregister(generation)
            listener.onFailed(FailReason.CaptureStartFailed)
        }
    }

    override fun stop() {
        // Unregister first: the service's teardown fires projection callbacks we must not see.
        CaptureBus.unregister(generation)
        launcher.stop()
    }
}
