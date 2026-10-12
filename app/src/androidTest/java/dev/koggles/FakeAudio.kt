package dev.koggles

import dev.koggles.audio.AudioSource
import dev.koggles.audio.AudioSourceListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Blueprint Task 21: a test audio source. A 220 Hz sine, amplitude-modulated at 2 Hz, pushed
 * through `pushPcmFloat` from its own thread; the audio state reaches `Active` through the
 * [AppGraph.overrideAudioForTest] source factory. Install before launching the activity.
 */
object FakeAudio {
    fun install() = AppGraph.overrideAudioForTest { _, _, _ -> Source() }

    fun uninstall() = AppGraph.overrideAudioForTest(null)

    private class Source : AudioSource {
        @Volatile private var running = false
        private var thread: Thread? = null

        override fun start(listener: AudioSourceListener) {
            running = true
            thread = Thread {
                val frames = 1024
                val buf = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                var t = 0.0
                val dt = 1.0 / 44_100.0
                while (running) {
                    buf.clear()
                    repeat(frames) {
                        val v = (sin(2 * PI * 220.0 * t) * (0.5 + 0.5 * sin(2 * PI * 2.0 * t))).toFloat()
                        buf.put(v); buf.put(v)
                        t += dt
                    }
                    NativeBridge.pushPcmFloat(buf, frames)
                    Thread.sleep(23)
                }
            }.also { it.start() }
            listener.onStarted()
        }

        override fun stop() {
            running = false
            thread?.join(1000)
            thread = null
        }
    }
}
