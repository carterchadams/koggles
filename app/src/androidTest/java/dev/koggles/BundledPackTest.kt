package dev.koggles

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preset plan M1: the APK bundles exactly the hash-pinned allowlist (app/bundled-presets.txt, packaged
 * into this test APK): the same flattened file names under presets/Koggles Starter Pack/, the same
 * bytes, and nothing else under presets/.
 */
@RunWith(AndroidJUnit4::class)
class BundledPackTest {
    private val appAssets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    /** File name -> SHA-256, from the allowlist's "<sha256>  <path>" lines. */
    private fun allowlist(): Map<String, String> =
        testAssets.open("bundled-presets.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { it.substringAfter("  ").substringAfterLast('/') to it.substringBefore("  ") }

    private fun sha256(path: String): String = appAssets.open(path).use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes())
    }.joinToString("") { "%02x".format(it) }

    @Test fun onlyTheStarterPackIsBundled() = assertEquals(listOf(PACK), appAssets.list("presets")!!.toList())

    @Test fun packFiles_matchTheAllowlist_byNameAndContent() {
        val want = allowlist()
        val have = appAssets.list("presets/$PACK")!!.toSet()
        val missing = (want.keys - have).sorted()
        val extra = (have - want.keys).sorted()
        val changed = (want.keys intersect have).filter { sha256("presets/$PACK/$it") != want.getValue(it) }.sorted()
        assertTrue("missing=$missing extra=$extra changed=$changed", missing.isEmpty() && extra.isEmpty() && changed.isEmpty())
        assertEquals(want.size, have.size)
    }

    companion object {
        const val PACK = "Koggles Starter Pack"
    }
}
