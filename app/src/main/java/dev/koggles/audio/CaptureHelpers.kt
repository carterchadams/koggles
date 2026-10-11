package dev.koggles.audio

import java.nio.ByteBuffer

/**
 * Acquires capture resources in order and, if a later step fails, releases what was already
 * acquired before rethrowing.
 */
internal class CaptureStartup<P, R>(
    private val stopProjection: (P) -> Unit,
    private val releaseRecord: (R) -> Unit,
) {
    fun start(acquire: () -> P, build: (P) -> R, begin: (R) -> Unit): Pair<P, R> {
        val projection = acquire()
        val record = try {
            build(projection)
        } catch (e: Throwable) {
            runCatching { stopProjection(projection) }
            throw e
        }
        try {
            begin(record)
        } catch (e: Throwable) {
            runCatching { releaseRecord(record) }
            runCatching { stopProjection(projection) }
            throw e
        }
        return projection to record
    }
}

/**
 * Blocking read loop. A negative read while still running is an unexpected failure (e.g. a dead
 * AudioRecord); one after [isRunning] turned false is the intentional stop unblocking the read.
 */
internal class CaptureReader(
    private val buffer: ByteBuffer,
    private val read: (ByteBuffer) -> Int,
    private val push: (bytes: Int) -> Unit,
    private val isRunning: () -> Boolean,
    private val onUnexpectedError: () -> Unit,
) {
    fun run() {
        while (isRunning()) {
            buffer.clear()
            val n = read(buffer)
            when {
                n > 0 -> push(n)
                n < 0 -> {
                    if (isRunning()) onUnexpectedError()
                    return
                }
            }
        }
    }
}

/** AudioRecord.RECORDSTATE_RECORDING. */
internal const val RECORDSTATE_RECORDING = 3

/**
 * Starts recording and verifies it took effect: startRecording() can return normally while the
 * native start failed, leaving the record stopped.
 */
internal fun requireRecording(startRecording: () -> Unit, recordingState: () -> Int) {
    startRecording()
    check(recordingState() == RECORDSTATE_RECORDING) { "AudioRecord did not enter the recording state" }
}
