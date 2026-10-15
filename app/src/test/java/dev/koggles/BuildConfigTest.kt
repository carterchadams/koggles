package dev.koggles

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsKoggles() {
        assertEquals("dev.koggles", BuildConfig.APPLICATION_ID)
    }

    /**
     * Unit tests run against the debug build, and a debug build never carries the Aptabase key, even on a machine
     * where -PkogglesAptabaseKey is set (the author's). Only the release build type takes the key, so development
     * installs and test runs can never report into the dashboard, and this test holds everywhere.
     */
    @Test
    fun debugBuildNeverCarriesTheAptabaseKey() {
        assertEquals("", BuildConfig.APTABASE_KEY)
    }
}
