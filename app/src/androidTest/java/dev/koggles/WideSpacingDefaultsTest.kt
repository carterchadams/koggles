package dev.koggles

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.render.WideSpacingDefaults
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wide views' default spacing on the real SDK (the author, 2026-10-13). The derivation in the research note
 * (.tmp/records/docs/research/cardboard-eye-bleed.md) gives +21.8 mm (Wide) and +8.6 mm (Wide + window) for the
 * emulator's 145.1 mm wide display at the V1 profile; the general criterion holds on any device: at the solved
 * offset the frame centre lands where the normal view puts it, within a pixel.
 */
@RunWith(AndroidJUnit4::class)
class WideSpacingDefaultsTest {
    // The display as AppGraph measures it (landscape: the long side is the width).
    private val bounds = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
    private val w = maxOf(bounds.width(), bounds.height())
    private val h = minOf(bounds.width(), bounds.height())
    private fun centreX(offset: Float, mode: Int) = NativeBridge.lensFrameCentreX(w, h, offset, mode)

    private lateinit var saved: dev.koggles.settings.SettingsValues

    @Before fun save() { saved = runBlocking { AppGraph.settings.values.first() } }

    @After fun restore() = runBlocking {
        val s = saved
        AppGraph.settings.update {
            it.copy(
                lensOffsetMm = s.lensOffsetMm, lensOffsetWideMm = s.lensOffsetWideMm, lensOffsetWideWindowMm = s.lensOffsetWideWindowMm,
                wideSpacingManual = s.wideSpacingManual, wideWindowSpacingManual = s.wideWindowSpacingManual, wideDefaultsFor = s.wideDefaultsFor,
            )
        }
        Unit
    }

    @Test fun frameCentre_normalView_sitsOnTheLeftLensAxis() {
        // V1 profile, 60 mm between lenses: the left axis is (W - 60 mm) / 2 from the left edge, left of centre.
        val x = centreX(0f, 0)
        assertFalse("mapping available", x.isNaN())
        assertTrue("left half: $x", x in 0.2f..0.35f)
        // A +10 mm offset moves the modelled axis 5 mm outward (the research note measured 704 → 621 px on the
        // emulator, whose 2400 px span 145.1 mm; here in the device's own pixels per mm).
        val pxPerMm = w / (w / InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.xdpi * 25.4f)
        val moved = centreX(10f, 0)
        assertTrue("outward by about 5 mm: ${(x - moved) * w / pxPerMm} mm", abs((x - moved) * w / pxPerMm - 5f) < 0.4f)
    }

    @Test fun solvedOffsets_putTheFrameCentreOnTheNormalAxis_withinAPixel() {
        val axis = centreX(0f, 0)
        for (mode in 1..2) {
            val offset = WideSpacingDefaults.solve(0f, mode, ::centreX)
            assertFalse("mode $mode solved", offset.isNaN())
            val err = (centreX(offset, mode) - axis) * w
            assertTrue("mode $mode: offset $offset leaves the centre ${err} px off the axis", abs(err) <= 1f)
        }
    }

    @Test fun emulatorDefaults_matchTheDerivation() {
        assumeTrue("emulator only (145.1 mm display)", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.startsWith("sdk"))
        val wide = WideSpacingDefaults.solve(0f, 1, ::centreX)
        val window = WideSpacingDefaults.solve(0f, 2, ::centreX)
        assertTrue("Wide $wide ≈ +21.8", abs(wide - 21.8f) <= 0.5f)
        assertTrue("Wide + window $window ≈ +8.6", abs(window - 8.6f) <= 0.5f)
    }

    /** The coordinator as wired in AppGraph: a fresh stamp makes it compute both defaults into the settings. */
    @Test fun appGraph_computesTheDefaults_onRecheck() = runBlocking {
        AppGraph.settings.update {
            it.copy(lensOffsetMm = 0f, lensOffsetWideMm = 0f, lensOffsetWideWindowMm = 0f, wideSpacingManual = false, wideWindowSpacingManual = false, wideDefaultsFor = null)
        }
        AppGraph.wideSpacingDefaults.recheck().join()
        val v = withTimeout(5_000) { AppGraph.settings.values.first { it.wideDefaultsFor != null } }
        val expectWide = WideSpacingDefaults.solve(0f, 1, ::centreX)
        val expectWindow = WideSpacingDefaults.solve(0f, 2, ::centreX)
        assertEquals(Math.round(expectWide * 2) / 2f, v.lensOffsetWideMm, 0f)
        assertEquals(Math.round(expectWindow * 2) / 2f, v.lensOffsetWideWindowMm, 0f)
        assertTrue(v.wideDefaultsFor!!, v.wideDefaultsFor!!.startsWith("${w}x$h/0.0/"))
    }
}
