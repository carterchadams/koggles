package dev.koggles.audio

import dev.koggles.NativeBridge
import java.nio.FloatBuffer

/** Where gated PCM goes; production is the native AudioWindow. */
interface PcmSink {
    fun pushFloat(buf: FloatBuffer, frames: Int)
    fun pushU8(buf: ByteArray, frames: Int)
    fun flush()
}

object NativePcmSink : PcmSink {
    override fun pushFloat(buf: FloatBuffer, frames: Int) = NativeBridge.pushPcmFloat(buf, frames)
    override fun pushU8(buf: ByteArray, frames: Int) = NativeBridge.pushPcmU8(buf, frames)
    override fun flush() = NativeBridge.flushAudio()
}

/**
 * Admits PCM only from the currently open source generation. [close] and [flush] share the
 * lock with every push, so once a source is closed and the window flushed, no write from that
 * source can land — even if its producer thread is still winding down, so a flushed native PCM
 * window can't be refilled with stale audio.
 */
open class PcmGate(private val sink: PcmSink) {
    private val lock = Any()
    private var openGeneration = 0L // 0 = closed

    open fun open(generation: Long) = synchronized(lock) { openGeneration = generation }

    open fun close() = synchronized(lock) { openGeneration = 0L }

    fun flush() = synchronized(lock) { sink.flush() }

    fun pushFloat(generation: Long, buf: FloatBuffer, frames: Int) = synchronized(lock) {
        if (generation != 0L && generation == openGeneration) sink.pushFloat(buf, frames)
    }

    fun pushU8(generation: Long, buf: ByteArray, frames: Int) = synchronized(lock) {
        if (generation != 0L && generation == openGeneration) sink.pushU8(buf, frames)
    }
}
