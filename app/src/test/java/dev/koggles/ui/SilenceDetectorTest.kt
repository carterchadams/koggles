package dev.koggles.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private fun SilenceDetector.feed(rms: Float, fromMs: Long, toMs: Long): Boolean {
        var last = false
        var t = fromMs
        while (t <= toMs) { last = sample(rms, t); t += 250 }
        return last
    }

    @Test fun quiet10s_hint() {
        val d = SilenceDetector()
        assertTrue(d.feed(0f, 0, 10_000))
    }

    @Test fun quiet9_9s_noHint() {
        val d = SilenceDetector()
        d.feed(0f, 0, 9_750)
        assertFalse(d.sample(0f, 9_900))
    }

    @Test fun soundResets() {
        val d = SilenceDetector()
        assertTrue(d.feed(0f, 0, 10_000))
        assertFalse(d.sample(0.5f, 10_250))
        assertFalse(d.feed(0f, 10_500, 20_000))
        assertTrue(d.sample(0f, 20_500))
    }

    @Test fun thresholdIsExclusive() {
        val d = SilenceDetector(threshold = 1e-3f)
        assertFalse("rms equal to the threshold is sound", d.feed(1e-3f, 0, 20_000))
    }
}
