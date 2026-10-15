package dev.koggles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsKoggles() {
        assertEquals("dev.koggles", BuildConfig.APPLICATION_ID)
    }

    /**
     * What counts as real data (the author, 2026-10-15): only an unsuffixed release build reports release events.
     * The debug build type and any build with an app id suffix (.smoke, .brand, .sweep) report as debug data, which
     * the dashboard shows separately, so development and test use never pollute the release numbers. Unit tests run
     * against the debug build, so the flag is true here whatever key the machine passes.
     */
    @Test
    fun debugAndSuffixedBuildsReportAsDebugData() {
        assertTrue(BuildConfig.APTABASE_DEBUG)
        assertEquals(BuildConfig.DEBUG || BuildConfig.APPLICATION_ID != "dev.koggles", BuildConfig.APTABASE_DEBUG)
    }
}
