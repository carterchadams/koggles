package dev.koggles

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsKoggles() {
        assertEquals("dev.koggles", BuildConfig.APPLICATION_ID)
    }

    /** Without -PkogglesAptabaseKey (plain CI, forks, local builds) the key is empty and analytics is unavailable. */
    @Test
    fun aptabaseKeyIsEmptyWithoutTheGradleProperty() {
        assertEquals("", BuildConfig.APTABASE_KEY)
    }
}
