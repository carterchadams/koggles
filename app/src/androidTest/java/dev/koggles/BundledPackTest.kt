package dev.koggles

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preset plan M1: the APK bundles exactly the hash-pinned allowlist (app/bundled-presets.txt, packaged into
 * this test APK), flat: the same names, the same bytes, no subfolder at all (the `! Transition/` folder went
 * with the interstitial feature, 2026-10-13), and nothing else under presets/.
 */
@RunWith(AndroidJUnit4::class)
class BundledPackTest {
    private val appAssets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    /** Pack-relative path -> SHA-256: the classic presets, flat. */
    private fun allowlist(): Map<String, String> =
        entries("bundled-presets.txt").associate { (hash, path) -> path.substringAfterLast('/') to hash }

    private fun entries(asset: String): List<Pair<String, String>> =
        testAssets.open(asset).bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBefore("  ") to it.substringAfter("  ") }

    /** Every file under presets/<PACK>, recursively, as pack-relative paths; and the subfolders seen. */
    private fun bundled(dir: String = "presets/$PACK", rel: String = "", dirs: MutableSet<String> = mutableSetOf()): Pair<Set<String>, Set<String>> {
        val files = mutableSetOf<String>()
        for (name in appAssets.list(dir)!!) {
            val path = if (rel.isEmpty()) name else "$rel/$name"
            val children = appAssets.list("$dir/$name")!!
            if (children.isEmpty()) files += path else { dirs += path; files += bundled("$dir/$name", path, dirs).first }
        }
        return files to dirs
    }

    private fun sha256(path: String): String = appAssets.open(path).use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes())
    }.joinToString("") { "%02x".format(it) }

    @Test fun onlyTheStarterPackIsBundled() = assertEquals(listOf(PACK), appAssets.list("presets")!!.toList())

    @Test fun packFiles_matchTheAllowlists_byPathAndContent() {
        val want = allowlist()
        val (have, dirs) = bundled()
        val missing = (want.keys - have).sorted()
        val extra = (have - want.keys).sorted()
        val changed = (want.keys intersect have).filter { sha256("presets/$PACK/$it") != want.getValue(it) }.sorted()
        assertTrue("missing=$missing extra=$extra changed=$changed", missing.isEmpty() && extra.isEmpty() && changed.isEmpty())
        assertEquals("no subfolder may be nested", emptySet<String>(), dirs)
    }

    companion object {
        const val PACK = "Koggles Starter Pack"
    }
}
