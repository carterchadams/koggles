package dev.koggles

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
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

    @Test fun everyBundledPreset_loadsWithItsOwnShaders() {
        val assets = AppGraph.app.assets
        val pack = "presets/${BundledPackTest.PACK}"
        val extra = InstrumentationRegistry.getArguments().getString("extraPresets").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val paths = assets.list(pack)!!.sorted().map { "$pack/$it" } + extra
        assertTrue("no bundled presets under $pack", paths.size > extra.size)
        val bad = PresetProbe.withRenderer { probe ->
            paths.mapNotNull { path ->
                val data = assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) } // as readPresetText does
                val r = probe.load(data)
                Log.i(TAG, "${r.shaderClass}\tms=${r.ms}\t$path\t${r.firstError}")
                val ok = r.shaderClass == "compiled" || (!r.diagnosticsOn && r.shaderClass == "loaded-unverified")
                if (ok) null else "$path: ${r.shaderClass} ${r.firstError}".trim()
            }
        }
        Log.i(TAG, "SUMMARY total=${paths.size} bad=${bad.size} diagnostics=${NativeBridge.diagnosticsEnabled()}")
        assertTrue("${bad.size} of ${paths.size} presets failed:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
