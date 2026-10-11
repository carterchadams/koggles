package dev.koggles.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerSourceTest {

    private class FakeVisualizer(
        val captureSizeResult: Int = VisualizerApi.SUCCESS,
        val listenerResult: Int = VisualizerApi.SUCCESS,
        val enableResult: Int = VisualizerApi.SUCCESS,
    ) : VisualizerApi {
        var released = false
        var enabled = false
        override val maxCaptureSize = 1024
        override val maxCaptureRate = 20_000
        override fun setCaptureSize(size: Int) = captureSizeResult
        override fun setWaveformListener(rate: Int, onWaveform: (ByteArray) -> Unit) = listenerResult
        override fun setEnabled(enabled: Boolean): Int { if (enableResult == VisualizerApi.SUCCESS) this.enabled = enabled; return enableResult }
        override fun release() { released = true }
    }

    private class RecordingListener : AudioSourceListener {
        val events = mutableListOf<String>()
        override fun onStarted() { events += "started" }
        override fun onFailed(reason: FailReason) { events += "failed:$reason" }
        override fun onProjectionStopped() { events += "projectionStopped" }
    }

    private fun startWith(fake: FakeVisualizer?): Pair<RecordingListener, FakeVisualizer?> {
        val listener = RecordingListener()
        VisualizerSource { fake ?: throw UnsupportedOperationException("no visualizer") }.start(listener)
        return listener to fake
    }

    @Test
    fun allSetupSucceeds_reportsStarted() {
        val (l, v) = startWith(FakeVisualizer())
        assertEquals(listOf("started"), l.events)
        assertTrue(v!!.enabled)
    }

    @Test
    fun constructionThrows_reportsUnavailable() {
        val (l, _) = startWith(null)
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
    }

    @Test
    fun captureSizeErrorCode_reportsUnavailableAndReleases() {
        val (l, v) = startWith(FakeVisualizer(captureSizeResult = -3))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue(v!!.released)
    }

    @Test
    fun listenerErrorCode_reportsUnavailableAndReleases() {
        val (l, v) = startWith(FakeVisualizer(listenerResult = -5))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue(v!!.released)
    }

    @Test
    fun enableErrorCode_reportsUnavailableAndReleases() {
        val (l, v) = startWith(FakeVisualizer(enableResult = -7))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue(v!!.released)
    }
}
