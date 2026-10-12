package dev.koggles.ui

/**
 * Spec §7: a menu hint after [windowMs] of continuous silence. A sample is silent when its RMS is
 * strictly below [threshold]; any louder sample restarts the window.
 */
class SilenceDetector(private val threshold: Float = 1e-3f, private val windowMs: Long = 10_000) {
    private var quietSince = -1L

    /** Records one sample; returns true while the hint should show. */
    fun sample(rms: Float, nowMs: Long): Boolean {
        if (rms >= threshold) {
            quietSince = -1L
            return false
        }
        if (quietSince < 0) quietSince = nowMs
        return nowMs - quietSince >= windowMs
    }

    fun reset() { quietSince = -1L }
}
