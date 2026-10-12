package dev.koggles

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostics build must expose shader failures that projectM swallows: a preset with a broken
 * warp or composite shader still "loads", so the classification has to come from projectM's own
 * messages. Runs only in diagnostics builds (-PkogglesShaderDiagnostics).
 */
@RunWith(AndroidJUnit4::class)
class ShaderDiagnosticsTest {
    companion object {
        @BeforeClass @JvmStatic fun grantAudio() {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.executeShellCommand("pm grant ${i.targetContext.packageName} android.permission.RECORD_AUDIO").close()
        }
    }

    @Before fun setUp() {
        assumeTrue("diagnostics build only (-PkogglesShaderDiagnostics)", NativeBridge.diagnosticsEnabled())
        FakeAudio.install()
        runBlocking { AppGraph.settings.update { it.copy(locked = true, goggles = false, folderPrompted = true, explainerSeen = true) } }
    }

    @After fun tearDown() {
        runBlocking { AppGraph.settings.update { it.copy(locked = false) } }
        FakeAudio.uninstall()
    }

    private fun classOf(asset: String): String = PresetProbe.withRenderer { probe ->
        val data = AppGraph.app.assets.open(asset).bufferedReader().use { it.readText() }
        probe.load(data).also { android.util.Log.i("KogglesDiag", "$asset -> ${it.shaderClass} ${it.firstError}") }.shaderClass
    }

    @Test fun validShaders_areReportedAsCompiled() = assertEquals("compiled", classOf("g1/ok.milk"))

    @Test fun brokenWarpShader_isReportedAsWarpFallback() = assertEquals("warp-fallback", classOf("g1/bad-warp.milk"))

    @Test fun brokenCompositeShader_isReportedAsCompositeFallback() = assertEquals("composite-fallback", classOf("g1/bad-comp.milk"))
}
