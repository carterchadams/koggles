package dev.koggles.render

import dev.koggles.settings.LENS_OFFSET_RANGE
import dev.koggles.settings.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The wide views' default lens spacing (the author, 2026-10-13: "the defaults for the wide modes should be set by
 * default and if google cardboard ever reads a qr code to change the spacing of the normal mode or if spacing is
 * changed for normal mode ... recalculate and set. But if the wide modes have ever been manually set, do not alter
 * those settings.").
 *
 * The normal view is taken as right. A wide view fuses when each eye's frame centre lands on the same screen point
 * as in the normal view, and where the SDK puts that centre depends on the modelled inter-lens distance twice: the
 * spacing offset moves the lens axis, and the field of view (clamped to the screen edges in the wide views) moves
 * the centre relative to the axis. So the default is the offset at which the two agree, found by bisection on the
 * SDK's own mapping ([frameCentreX], the frame centre's screen x for the left eye).
 *
 * Every computation is stamped with its inputs ([Inputs] and the normal spacing) in the settings; a recheck
 * recomputes only when they differ, and writes only the views not set by hand. Runs on [scope] (main);
 * the lens maths on [compute].
 */
class WideSpacingDefaults(
    private val settings: Settings,
    private val inputs: () -> Inputs,
    private val frameCentreX: (displayW: Int, displayH: Int, offsetMm: Float, wideView: Int) -> Float,
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher,
) {
    /** The landscape display in pixels and a digest of the saved viewer profile. */
    data class Inputs(val displayW: Int, val displayH: Int, val profile: String)

    private val lock = Mutex()

    /** Rechecks on every change of the normal spacing (the slider, a QR scan's reset). */
    fun start() {
        scope.launch {
            settings.values.map { it.lensOffsetMm }.distinctUntilChanged().collect { recheck() }
        }
    }

    /** Rechecks now (on resume: a QR scan may have changed the profile without touching the settings). */
    fun recheck(): Job = scope.launch {
        lock.withLock {
            val v = settings.values.first()
            val input = inputs()
            val key = stamp(input, v.lensOffsetMm)
            if (v.wideDefaultsFor == key) return@withLock
            val wide = if (v.wideSpacingManual) null else solveOn(input, v.lensOffsetMm, 1)
            val window = if (v.wideWindowSpacingManual) null else solveOn(input, v.lensOffsetMm, 2)
            settings.update { cur ->
                // The normal spacing may have moved meanwhile: then this result is stale and the next recheck redoes it.
                if (stamp(inputs(), cur.lensOffsetMm) != key) return@update cur
                cur.copy(
                    lensOffsetWideMm = wide?.takeUnless { it.isNaN() || cur.wideSpacingManual } ?: cur.lensOffsetWideMm,
                    lensOffsetWideWindowMm = window?.takeUnless { it.isNaN() || cur.wideWindowSpacingManual } ?: cur.lensOffsetWideWindowMm,
                    wideDefaultsFor = key,
                )
            }
        }
    }

    private suspend fun solveOn(input: Inputs, normalMm: Float, wideView: Int): Float = withContext(compute) {
        solve(normalMm, wideView) { offset, mode -> frameCentreX(input.displayW, input.displayH, offset, mode) }
    }

    private fun stamp(input: Inputs, normalMm: Float) = "${input.displayW}x${input.displayH}/$normalMm/${input.profile}"

    companion object {
        /** Bisection stops at this width; the slider's 0.5 mm step rounds the result on write. */
        private const val TOLERANCE_MM = 0.05f

        /**
         * The spacing offset (mm, in [LENS_OFFSET_RANGE], applied as the view's own offset) at which [wideView]'s
         * frame centre lands where the normal view's does at [normalMm], given [centreX] (offset, view) → screen x.
         * Without a crossing in the range, the end that comes closest; NaN if the mapping fails.
         */
        fun solve(normalMm: Float, wideView: Int, centreX: (offsetMm: Float, wideView: Int) -> Float): Float {
            val axis = centreX(normalMm, 0)
            fun f(offset: Float) = centreX(offset, wideView) - axis
            var lo = LENS_OFFSET_RANGE.start
            var hi = LENS_OFFSET_RANGE.endInclusive
            var fLo = f(lo)
            var fHi = f(hi)
            if (fLo.isNaN() || fHi.isNaN() || axis.isNaN()) return Float.NaN
            if (fLo == 0f) return lo
            if (fHi == 0f) return hi
            if ((fLo < 0f) == (fHi < 0f)) return if (kotlin.math.abs(fLo) <= kotlin.math.abs(fHi)) lo else hi
            while (hi - lo > TOLERANCE_MM) {
                val mid = (lo + hi) / 2
                val fMid = f(mid)
                if (fMid.isNaN()) return Float.NaN
                if ((fMid < 0f) == (fLo < 0f)) { lo = mid; fLo = fMid } else { hi = mid; fHi = fMid }
            }
            return (lo + hi) / 2
        }
    }
}
