package dev.koggles

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsKoggles() {
        assertEquals("dev.koggles", BuildConfig.APPLICATION_ID)
    }
}
