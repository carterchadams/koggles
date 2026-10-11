package dev.koggles.audio

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CaptureHelpersTest {
    private val log = mutableListOf<String>()
    private val startup = CaptureStartup<String, String>(
        stopProjection = { log += "stopProjection($it)" },
        releaseRecord = { log += "releaseRecord($it)" },
    )

    @Test
    fun allStepsSucceed_returnsBothWithoutReleasing() {
        val (p, r) = startup.start({ "P" }, { "R" }, { log += "begin($it)" })
        assertEquals("P" to "R", p to r)
        assertEquals(listOf("begin(R)"), log)
    }

    @Test
    fun recordConstructionFails_stopsProjectionAndRethrows() {
        try {
            startup.start({ "P" }, { error("no record") }, { })
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals(listOf("stopProjection(P)"), log)
        }
    }

    @Test
    fun startRecordingFails_releasesRecordThenStopsProjection() {
        try {
            startup.start({ "P" }, { "R" }, { error("start failed") })
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals(listOf("releaseRecord(R)", "stopProjection(P)"), log)
        }
    }

    @Test
    fun reader_unexpectedReadError_whileRunning_reportsFailure() {
        var running = true
        var failed = 0
        val reads = ArrayDeque(listOf(8, 8, -6))
        CaptureReader(
            buffer = ByteBuffer.allocateDirect(64),
            read = { reads.removeFirst() },
            push = { },
            isRunning = { running },
            onUnexpectedError = { failed++ },
        ).run()
        assertEquals(1, failed)
    }

    @Test
    fun reader_errorAfterIntentionalStop_isNotReported() {
        var running = true
        var failed = 0
        CaptureReader(
            buffer = ByteBuffer.allocateDirect(64),
            read = { running = false; -3 }, // stop() unblocks read with an error
            push = { },
            isRunning = { running },
            onUnexpectedError = { failed++ },
        ).run()
        assertEquals(0, failed)
    }

    @Test
    fun reader_pushesFramesForPositiveReads() {
        var running = true
        val pushed = mutableListOf<Int>()
        val reads = ArrayDeque(listOf(16, 8))
        CaptureReader(
            buffer = ByteBuffer.allocateDirect(64),
            read = { if (reads.isEmpty()) { running = false; 0 } else reads.removeFirst() },
            push = { bytes -> pushed += bytes },
            isRunning = { running },
            onUnexpectedError = { },
        ).run()
        assertTrue(pushed == listOf(16, 8))
    }

    @Test
    fun requireRecording_startReturnsButNotRecording_throws() {
        try {
            requireRecording(startRecording = { }, recordingState = { 1 /* RECORDSTATE_STOPPED */ })
            fail("expected exception")
        } catch (e: IllegalStateException) {
        }
    }

    @Test
    fun requireRecording_recording_passes() {
        requireRecording(startRecording = { }, recordingState = { RECORDSTATE_RECORDING })
    }
}
