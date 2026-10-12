package dev.koggles.audio

import android.media.audiofx.Visualizer

/** The subset of [Visualizer] the source uses; every setter returns a Visualizer status code. */
interface VisualizerApi {
    /** Whether the effect is enabled (another client may already run session 0). */
    val enabled: Boolean
    val maxCaptureSize: Int
    val maxCaptureRate: Int
    fun setCaptureSize(size: Int): Int
    fun setWaveformListener(rate: Int, onWaveform: (ByteArray) -> Unit): Int
    fun setEnabled(enabled: Boolean): Int
    fun release()

    companion object {
        const val SUCCESS = 0 // Visualizer.SUCCESS
        const val ERROR_INVALID_OPERATION = -5 // Visualizer.ERROR_INVALID_OPERATION
    }
}

/** Output mix (session 0). Construction may throw on devices that don't support it. */
class AndroidVisualizer : VisualizerApi {
    private val v = Visualizer(0)
    override val enabled get() = v.enabled
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
 * after every setup call succeeds. Another client holding session 0 shows up as an
 * IllegalStateException or ERROR_INVALID_OPERATION from a setup call: that is
 * [FailReason.VisualizerBusy], never "unavailable" (it was once misreported as the phone not
 * sharing its audio at all, when another visualizer app merely held the session). Every failure logs each
 * call made and its result; retries belong to the audio machine, never here.
 */
class VisualizerSource(
    private val onWaveform: (ByteArray) -> Unit = {},
    private val create: () -> VisualizerApi = ::AndroidVisualizer,
    private val log: (String) -> Unit = {},
) : AudioSource {
    private var visualizer: VisualizerApi? = null

    override fun start(listener: AudioSourceListener) {
        val calls = mutableListOf<String>()
        var enabledOnCreate: Boolean? = null
        val failure: FailReason? = run {
            val v = try {
                create().also { visualizer = it }
            } catch (e: RuntimeException) {
                calls += "create threw ${e.describe()}"
                return@run FailReason.VisualizerUnavailable
            }
            enabledOnCreate = runCatching { v.enabled }.getOrNull()
            // An instance created while another client runs session 0 can't change its capture size.
            if (enabledOnCreate != true) {
                step(calls, "setCaptureSize(${v.maxCaptureSize})") { v.setCaptureSize(v.maxCaptureSize) }
                    ?.let { return@run it }
            }
            step(calls, "setWaveformListener(${v.maxCaptureRate})") { v.setWaveformListener(v.maxCaptureRate, onWaveform) }
                ?.let { return@run it }
            step(calls, "setEnabled(true)") { v.setEnabled(true) }
        }
        if (failure == null) {
            listener.onStarted()
        } else {
            log("Visualizer setup failed ($failure): enabledOnCreate=$enabledOnCreate; ${calls.joinToString("; ")}")
            release()
            listener.onFailed(failure)
        }
    }

    /** Runs one setup call, records it, and classifies a failure (null = success). */
    private inline fun step(calls: MutableList<String>, name: String, call: () -> Int): FailReason? {
        val code = try {
            call()
        } catch (e: IllegalStateException) {
            calls += "$name threw ${e.describe()}"
            return FailReason.VisualizerBusy
        } catch (e: RuntimeException) {
            calls += "$name threw ${e.describe()}"
            return FailReason.VisualizerUnavailable
        }
        calls += "$name=$code"
        return when (code) {
            VisualizerApi.SUCCESS -> null
            VisualizerApi.ERROR_INVALID_OPERATION -> FailReason.VisualizerBusy
            else -> FailReason.VisualizerUnavailable
        }
    }

    private fun Throwable.describe() = "${javaClass.simpleName}: $message"

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
