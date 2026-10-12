package dev.koggles.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerSourceTest {

    private class FakeVisualizer(
        val captureSizeResult: Int = VisualizerApi.SUCCESS,
        val listenerResult: Int = VisualizerApi.SUCCESS,
        val enableResult: Int = VisualizerApi.SUCCESS,
        val captureSizeThrows: RuntimeException? = null,
        val enableThrows: RuntimeException? = null,
        enabledOnCreate: Boolean = false,
    ) : VisualizerApi {
        var released = false
        val calls = mutableListOf<String>()
        override var enabled = enabledOnCreate
        override val maxCaptureSize = 1024
        override val maxCaptureRate = 20_000
        override fun setCaptureSize(size: Int): Int {
            calls += "setCaptureSize"
            captureSizeThrows?.let { throw it }
            return captureSizeResult
        }
        override fun setWaveformListener(rate: Int, onWaveform: (ByteArray) -> Unit): Int {
            calls += "setWaveformListener"
            return listenerResult
        }
        override fun setEnabled(enabled: Boolean): Int {
            calls += "setEnabled($enabled)"
            if (enabled) enableThrows?.let { throw it }
            if (enableResult == VisualizerApi.SUCCESS) this.enabled = enabled
            return enableResult
        }
        override fun release() { released = true }
    }

    private class RecordingListener : AudioSourceListener {
        val events = mutableListOf<String>()
        override fun onStarted() { events += "started" }
        override fun onFailed(reason: FailReason) { events += "failed:$reason" }
        override fun onProjectionStopped() { events += "projectionStopped" }
    }

    private val logs = mutableListOf<String>()

    private fun startWith(fake: FakeVisualizer?): Pair<RecordingListener, FakeVisualizer?> {
        val listener = RecordingListener()
        VisualizerSource(
            create = { fake ?: throw UnsupportedOperationException("no visualizer") },
            log = { logs += it },
        ).start(listener)
        return listener to fake
    }

    private val illegalState get() = IllegalStateException("setCaptureSize() called in wrong state: 2")

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
    fun constructorThrows_reportsUnavailable_noSleep() {
        val t0 = System.nanoTime()
        val (l, _) = startWith(null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue("start took $ms ms", ms < 50)
    }

    @Test
    fun captureSizeErrorCode_reportsUnavailableAndReleases() {
        val (l, v) = startWith(FakeVisualizer(captureSizeResult = -3))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue(v!!.released)
    }

    @Test
    fun listenerErrorCode_reportsUnavailableAndReleases() {
        val (l, v) = startWith(FakeVisualizer(listenerResult = -3))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue(v!!.released)
    }

    @Test
    fun enableErrorCode_reportsUnavailableAndReleases() {
        val (l, v) = startWith(FakeVisualizer(enableResult = -7))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
        assertTrue(v!!.released)
    }

    @Test
    fun otherErrorCode_reportsUnavailable() {
        val (l, _) = startWith(FakeVisualizer(captureSizeResult = -1))
        assertEquals(listOf("failed:VisualizerUnavailable"), l.events)
    }

    @Test
    fun enabledOnCreate_skipsCaptureSize_listenerInvalidOp_reportsBusy() {
        val (l, v) = startWith(FakeVisualizer(enabledOnCreate = true, listenerResult = -5))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
        assertFalse(v!!.calls.contains("setCaptureSize"))
        assertTrue(v.released)
    }

    @Test
    fun captureSizeThrowsIllegalState_reportsBusy_notUnavailable() {
        val (l, v) = startWith(FakeVisualizer(captureSizeThrows = illegalState))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
        assertTrue(v!!.released)
    }

    @Test
    fun captureSizeReturnsInvalidOp_reportsBusy() {
        val (l, _) = startWith(FakeVisualizer(captureSizeResult = -5))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
    }

    @Test
    fun listenerReturnsInvalidOperation_reportsBusy() {
        val (l, _) = startWith(FakeVisualizer(listenerResult = -5))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
    }

    @Test
    fun enableReturnsInvalidOp_reportsBusy() {
        val (l, _) = startWith(FakeVisualizer(enableResult = -5))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
    }

    @Test
    fun enableThrowsIllegalState_reportsBusy() {
        val (l, _) = startWith(FakeVisualizer(enableThrows = IllegalStateException("setEnabled() called in wrong state")))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
    }

    @Test
    fun controlLostAfterCaptureSize_listenerInvalidOp_reportsBusy() {
        val (l, v) = startWith(FakeVisualizer(captureSizeResult = 0, listenerResult = -5))
        assertEquals(listOf("failed:VisualizerBusy"), l.events)
        assertTrue(v!!.calls.contains("setCaptureSize"))
    }

    @Test
    fun failure_logsEveryCallCodeAndEnabledState() {
        startWith(FakeVisualizer(captureSizeResult = 0, listenerResult = -5))
        assertEquals(1, logs.size)
        val line = logs.single()
        for (part in listOf("enabledOnCreate=false", "setCaptureSize(1024)=0", "setWaveformListener(20000)=-5", "VisualizerBusy")) {
            assertTrue("'$part' missing from: $line", line.contains(part))
        }
        logs.clear()
        startWith(FakeVisualizer(captureSizeThrows = illegalState))
        assertTrue(logs.single(), logs.single().contains("setCaptureSize(1024) threw IllegalStateException: setCaptureSize() called in wrong state: 2"))
        logs.clear()
        startWith(null)
        assertTrue(logs.single(), logs.single().contains("create threw UnsupportedOperationException: no visualizer"))
    }

    @Test
    fun success_logsNothing() {
        startWith(FakeVisualizer())
        assertEquals(emptyList<String>(), logs)
    }
}
