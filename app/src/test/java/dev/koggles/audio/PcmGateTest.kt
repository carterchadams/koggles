package dev.koggles.audio

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmGateTest {
    private class RecordingSink : PcmSink {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun pushFloat(buf: FloatBuffer, frames: Int) { events += "float:$frames" }
        override fun pushU8(buf: ByteArray, frames: Int) { events += "u8:$frames" }
        override fun flush() { events += "flush" }
    }

    private val sink = RecordingSink()
    private val gate = PcmGate(sink)

    @Test
    fun pushFromOpenGeneration_reachesSink() {
        gate.open(1)
        gate.pushU8(1, ByteArray(4), 4)
        assertEquals(listOf("u8:4"), sink.events)
    }

    @Test
    fun pushFromOtherGeneration_dropped() {
        gate.open(2)
        gate.pushU8(1, ByteArray(4), 4)
        gate.pushFloat(1, FloatBuffer.allocate(8), 4)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun closedGate_dropsEverything() {
        gate.open(1)
        gate.close()
        gate.pushU8(1, ByteArray(4), 4)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun noStaleWriteAfterCloseAndFlush_underConcurrency() {
        gate.open(1)
        val pushing = AtomicBoolean(true)
        val producer = thread {
            val buf = FloatBuffer.allocate(16)
            while (pushing.get()) gate.pushFloat(1, buf, 8)
        }
        Thread.sleep(20)
        gate.close()
        gate.flush()
        val afterFlush = sink.events.size
        Thread.sleep(50)
        pushing.set(false)
        producer.join()
        assertEquals("no writes after close+flush", afterFlush, sink.events.size)
        assertEquals("flush", sink.events.last())
    }
}
