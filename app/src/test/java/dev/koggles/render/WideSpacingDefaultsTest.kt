package dev.koggles.render

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.koggles.settings.Settings
import dev.koggles.settings.SettingsValues
import dev.koggles.settings.withLensOffsetForCurrentMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The wide views' default spacing (the author, 2026-10-13): computed from the viewer profile, the display and the
 * normal view's spacing; recomputed when any of those change; never touching a view that was set by hand.
 */
class WideSpacingDefaultsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("x.preferences_pb") }

    @After fun tearDown() = scope.cancel()

    // A model of the SDK: the frame centre's screen x (full-screen uv) moves outward with the offset in every
    // view, and a wide view's centre sits `root(mode)` mm further out than the normal view's at the same offset,
    // so it meets the normal view's centre at `root(mode) + normal`.
    private val evaluations = AtomicInteger()
    private var profile = "v1"
    private var display = WideSpacingDefaults.Inputs(2400, 1080, profile)
    private fun root(mode: Int) = if (mode == 1) 21.8f else 8.6f
    private val centreX: (Int, Int, Float, Int) -> Float = { _, _, offset, mode ->
        evaluations.incrementAndGet()
        if (mode == 0) 0.25f - 0.004f * offset else 0.25f - 0.004f * (offset - root(mode))
    }

    @Test fun solve_findsTheOffsetWhereTheCentreMeetsTheAxis() {
        val wide = WideSpacingDefaults.solve(1f, 1) { offset, mode -> centreX(2400, 1080, offset, mode) }
        val window = WideSpacingDefaults.solve(1f, 2) { offset, mode -> centreX(2400, 1080, offset, mode) }
        assertTrue("wide $wide", abs(wide - 22.8f) <= 0.05f)
        assertTrue("window $window", abs(window - 9.6f) <= 0.05f)
    }

    @Test fun solve_withoutARootInTheRange_picksTheNearestEnd() {
        // The centre never reaches the axis: the end of the range that comes closest.
        val always = { offset: Float, mode: Int -> if (mode == 0) 0.25f else 0.3f - 0.0001f * offset }
        assertEquals(40f, WideSpacingDefaults.solve(0f, 1, always), 0f)
        val never = { _: Float, mode: Int -> if (mode == 0) 0.25f else Float.NaN }
        assertTrue(WideSpacingDefaults.solve(0f, 1, never).isNaN())
    }

    private fun defaults(settings: Settings) = WideSpacingDefaults(
        settings = settings,
        inputs = { display.copy(profile = profile) },
        frameCentreX = centreX,
        scope = scope,
        compute = Dispatchers.Default,
    )

    private suspend fun Settings.await(what: String, p: (SettingsValues) -> Boolean): SettingsValues =
        withTimeout(5_000) { values.first { v -> p(v) } }.also { assertTrue(what, p(it)) }

    @Test fun firstRun_setsBothWideSpacings_toTheHalfMillimetre() = runBlocking {
        val settings = Settings(store())
        val d = defaults(settings)
        d.recheck().join()
        val v = settings.await("computed") { it.wideDefaultsFor != null }
        assertEquals(22f, v.lensOffsetWideMm, 0f)       // 21.8 to the slider's 0.5 mm step
        assertEquals(8.5f, v.lensOffsetWideWindowMm, 0f)
        assertFalse(v.wideSpacingManual); assertFalse(v.wideWindowSpacingManual)
        assertNotNull(v.wideDefaultsFor)
    }

    @Test fun unchangedInputs_computeNothing() = runBlocking {
        val settings = Settings(store())
        val d = defaults(settings)
        d.recheck().join()
        val n = evaluations.get()
        assertTrue("computed once", n > 0)
        d.recheck().join()
        assertEquals(n, evaluations.get())
    }

    @Test fun normalSpacingChange_recomputes() = runBlocking {
        val settings = Settings(store())
        settings.update { it.copy(lensOffsetMm = 3f) }
        val d = defaults(settings)
        d.start()
        settings.await("computed for +3") { it.lensOffsetWideMm == 25f && it.lensOffsetWideWindowMm == 11.5f }
        // The user moves the normal slider: both wide defaults follow, through the settings flow alone.
        settings.update { it.copy(lensOffsetMm = 0f) }
        settings.await("recomputed for 0") { it.lensOffsetWideMm == 22f && it.lensOffsetWideWindowMm == 8.5f }
        Unit
    }

    @Test fun profileChange_recomputes_butNeverAHandSetView() = runBlocking {
        val settings = Settings(store())
        val d = defaults(settings)
        d.recheck().join()
        settings.await("computed") { it.lensOffsetWideMm == 22f }
        // Wide set by hand, then a QR scan changes the viewer profile.
        settings.update { it.copy(wideView = 1).withLensOffsetForCurrentMode(5f) }
        profile = "v2"
        display = display.copy(displayW = 2800)
        d.recheck().join()
        val v = settings.await("window recomputed") { it.wideDefaultsFor?.contains("v2") == true }
        assertEquals(5f, v.lensOffsetWideMm, 0f)
        assertTrue(v.wideSpacingManual)
        assertEquals(8.5f, v.lensOffsetWideWindowMm, 0f)
    }
}
