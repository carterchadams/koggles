package dev.koggles

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterization sweep: loads every preset in /data/local/tmp/koggles-sweep/<set>.tsv (one
 * "name<TAB>base64(content)" line per preset, pushed with adb) and logs projectM's outcome for each
 * as a KogglesSweep line, classified by [PresetProbe] (run a -PkogglesShaderDiagnostics build to
 * detect swallowed shader fallbacks). It asserts nothing; it measures which presets work on this device.
 * Skipped when no sets are present.
 */
@RunWith(AndroidJUnit4::class)
class PresetCompileSweepTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    companion object {
        private const val DIR = "/data/local/tmp/koggles-sweep"
        private const val TAG = "KogglesSweep"

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

    private fun shell(cmd: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }

    @Test fun sweep() {
        val sets = String(shell("ls $DIR")).lines().map { it.trim() }.filter { it.endsWith(".tsv") }
        assumeTrue("no sweep sets in $DIR", sets.isNotEmpty())
        Log.i(TAG, "diagnostics=${NativeBridge.diagnosticsEnabled()}")
        PresetProbe.withRenderer { probe ->
            for (set in sets) {
                val lines = String(shell("cat $DIR/$set")).lines().filter { it.contains('\t') }
                val counts = sortedMapOf<String, Int>()
                for (line in lines) {
                    val (name, b64) = line.split('\t', limit = 2)
                    val data = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) // as readPresetText does
                    val r = probe.load(data)
                    counts.merge(r.shaderClass, 1, Int::plus)
                    Log.i(TAG, "set=$set\t${r.shaderClass}\tms=${r.ms}\t$name\t${(r.message + " " + r.firstError).trim().take(300)}")
                }
                Log.i(TAG, "SUMMARY set=$set total=${lines.size} " + counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
            }
        }
    }
}
