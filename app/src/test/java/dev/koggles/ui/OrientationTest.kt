package dev.koggles.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The author (2026-10-13): the menu is portrait whenever it is open, in both modes; closed, goggles mode is landscape. */
class OrientationTest {
    @Test fun menuOpen_isPortrait_inNormalMode() =
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientationFor(goggles = false, menuOpen = true))

    @Test fun menuOpen_isPortrait_inGogglesMode() =
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientationFor(goggles = true, menuOpen = true))

    @Test fun menuClosed_gogglesMode_isSensorLandscape() =
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientationFor(goggles = true, menuOpen = false))

    @Test fun menuClosed_normalMode_followsTheDevice() =
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, orientationFor(goggles = false, menuOpen = false))
}
