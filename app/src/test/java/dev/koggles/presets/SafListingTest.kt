package dev.koggles.presets

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class SafListingTest {
    @Test
    fun completeListing_passes() {
        requireCompleteListing(loading = false, error = null, what = "dir")
    }

    @Test
    fun loadingListing_isIncomplete() {
        assertThrows(IOException::class.java) { requireCompleteListing(loading = true, error = null, what = "dir") }
    }

    @Test
    fun errorListing_isIncomplete() {
        assertThrows(IOException::class.java) { requireCompleteListing(loading = false, error = "offline", what = "dir") }
    }
}
