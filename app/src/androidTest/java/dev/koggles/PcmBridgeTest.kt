package dev.koggles

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmBridgeTest {
    @Test
    fun pushPcmFloat_frameCountBeyondCapacity_isRejected() {
        // 8 stereo frames of capacity; claim a million. Must not read past the buffer.
        val buf = ByteBuffer.allocateDirect(8 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        NativeBridge.pushPcmFloat(buf, 1_000_000)
        NativeBridge.pushPcmFloat(buf, Int.MAX_VALUE)
        NativeBridge.pushPcmFloat(buf, -5)
        NativeBridge.flushAudio()
    }

    @Test
    fun pushPcmU8_frameCountBeyondArray_isClamped() {
        NativeBridge.pushPcmU8(ByteArray(16), 1_000_000)
        NativeBridge.flushAudio()
    }
}
