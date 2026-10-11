package dev.koggles.audio

import android.media.audiofx.Visualizer

/** The subset of [Visualizer] the source uses; every setter returns a Visualizer status code. */
interface VisualizerApi {
    val maxCaptureSize: Int
    val maxCaptureRate: Int
    fun setCaptureSize(size: Int): Int
    fun setWaveformListener(rate: Int, onWaveform: (ByteArray) -> Unit): Int
    fun setEnabled(enabled: Boolean): Int
    fun release()

    companion object {
        const val SUCCESS = 0 // Visualizer.SUCCESS
    }
}

/** Output mix (session 0). Construction may throw on devices that don't support it. */
class AndroidVisualizer : VisualizerApi {
    private val v = Visualizer(0)
    override val maxCaptureSize get() = Visualizer.getCaptureSizeRange()[1]
    override val maxCaptureRate get() = Visualizer.getMaxCaptureRate()
    override fun setCaptureSize(size: Int) = v.setCaptureSize(size)
    override fun setWaveformListener(rate: Int, onWaveform: (ByteArray) -> Unit) = v.setDataCaptureListener(
        object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray, samplingRate: Int) = onWaveform(waveform)
            override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
        },
        rate,
        true,
        false,
    )
    override fun setEnabled(enabled: Boolean) = v.setEnabled(enabled)
    override fun release() = v.release()
}

/**
 * "Standard" source: the output mix via [Visualizer] waveform capture. Reports started only
 * after every setup call returns SUCCESS; any exception or error code means the Visualizer is
 * unavailable on this device.
 */
class VisualizerSource(
    private val onWaveform: (ByteArray) -> Unit = {},
    private val create: () -> VisualizerApi = ::AndroidVisualizer,
) : AudioSource {
    private var visualizer: VisualizerApi? = null

    override fun start(listener: AudioSourceListener) {
        val ok = try {
            val v = create().also { visualizer = it }
            v.setCaptureSize(v.maxCaptureSize) == VisualizerApi.SUCCESS &&
                v.setWaveformListener(v.maxCaptureRate, onWaveform) == VisualizerApi.SUCCESS &&
                v.setEnabled(true) == VisualizerApi.SUCCESS
        } catch (e: RuntimeException) {
            false
        }
        if (ok) {
            listener.onStarted()
        } else {
            release()
            listener.onFailed(FailReason.VisualizerUnavailable)
        }
    }

    override fun stop() = release()

    private fun release() {
        visualizer?.let {
            runCatching { it.setEnabled(false) }
            it.release()
        }
        visualizer = null
    }
}

/** Fails immediately; used when High quality is started without a consent result. */
class UnavailableSource : AudioSource {
    override fun start(listener: AudioSourceListener) = listener.onFailed(FailReason.CaptureStartFailed)
    override fun stop() = Unit
}
