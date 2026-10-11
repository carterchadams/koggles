package dev.koggles.audio

import android.media.audiofx.Visualizer
import dev.koggles.NativeBridge

/** "Standard" source: the output mix (session 0) via [Visualizer] waveform capture. */
class VisualizerSource : AudioSource {
    private var visualizer: Visualizer? = null

    override fun start(listener: AudioSourceListener) {
        try {
            val v = Visualizer(0)
            visualizer = v
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray, samplingRate: Int) {
                        NativeBridge.pushPcmU8(waveform, waveform.size)
                    }

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
                },
                Visualizer.getMaxCaptureRate(),
                true,
                false,
            )
            v.enabled = true
            listener.onStarted()
        } catch (e: RuntimeException) {
            release()
            listener.onFailed(FailReason.VisualizerUnavailable)
        }
    }

    override fun stop() = release()

    private fun release() {
        visualizer?.let {
            runCatching { it.enabled = false }
            it.release()
        }
        visualizer = null
    }
}

/** Placeholder until Task 8 adds playback capture: fails immediately. */
class UnavailableSource : AudioSource {
    override fun start(listener: AudioSourceListener) = listener.onFailed(FailReason.CaptureStartFailed)
    override fun stop() = Unit
}
