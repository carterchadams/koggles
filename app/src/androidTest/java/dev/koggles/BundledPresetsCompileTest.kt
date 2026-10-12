package dev.koggles

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preset plan M2: every bundled preset loads with its own shaders. On a diagnostics build
 * (-PkogglesShaderDiagnostics) a warp or composite fallback is a failure, because projectM swallows
 * those; on other builds only failed or timed-out loads are caught. The instrumentation argument
 * extraPresets (comma-separated asset paths) adds presets to the run, for mutation checks.
 */
@RunWith(AndroidJUnit4::class)
class BundledPresetsCompileTest {
    companion object {
        private const val TAG = "KogglesBundled"

        @BeforeClass @JvmStatic fun grantAudio() {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.executeShellCommand("pm grant ${i.targetContext.packageName} android.permission.RECORD_AUDIO").close()
        }
    }

    @Before fun setUp() {
        FakeAudio.install()
        runBlocking { AppGraph.settings.update { it.copy(locked = true, goggles = false, folderPrompted = true, explainerSeen = true) } }
    }

    @After fun tearDown() {
        runBlocking { AppGraph.settings.update { it.copy(locked = false) } }
        FakeAudio.uninstall()
    }

    /** Every .milk asset under [dir], recursively (the pack is flat plus `! Transition/`). */
    private fun presetsUnder(assets: android.content.res.AssetManager, dir: String): List<String> =
        assets.list(dir)!!.sorted().flatMap { name ->
            val path = "$dir/$name"
            if (assets.list(path)!!.isNotEmpty()) presetsUnder(assets, path) else listOf(path).filter { it.endsWith(".milk") }
        }

    /** Load-only coverage, any build: no failed or timed-out loads. Shader fallbacks are invisible here. */
    @Test fun everyBundledPreset_loads() = sweep(requireOwnShaders = false)

    /**
     * The shader gate: every bundled preset's own warp and composite shaders compile. projectM
     * silently substitutes fallback shaders on failure, so this needs the diagnostics build
     * (-PkogglesShaderDiagnostics); without it the test is skipped rather than passing vacuously.
     */
    @Test fun everyBundledPreset_loadsWithItsOwnShaders() {
        assumeTrue("shader gate needs -PkogglesShaderDiagnostics", NativeBridge.diagnosticsEnabled())
        sweep(requireOwnShaders = true)
    }

    private fun sweep(requireOwnShaders: Boolean) {
        val assets = AppGraph.app.assets
        val pack = "presets/${BundledPackTest.PACK}"
        val extra = InstrumentationRegistry.getArguments().getString("extraPresets").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val paths = presetsUnder(assets, pack) + extra
        assertTrue("no bundled presets under $pack", paths.size > extra.size)
        val bad = PresetProbe.withRenderer { probe ->
            paths.mapNotNull { path ->
                val data = assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) } // as readPresetText does
                val r = probe.load(data)
                Log.i(TAG, "${r.shaderClass}\tms=${r.ms}\t$path\t${r.firstError}")
                val ok = if (requireOwnShaders) r.shaderClass == "compiled" else r.outcome == "loaded"
                if (ok) null else "$path: ${r.shaderClass} ${r.firstError}".trim()
            }
        }
        Log.i(TAG, "SUMMARY total=${paths.size} bad=${bad.size} diagnostics=${NativeBridge.diagnosticsEnabled()}")
        assertTrue("${bad.size} of ${paths.size} presets failed:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
