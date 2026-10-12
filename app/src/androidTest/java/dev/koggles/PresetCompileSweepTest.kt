package dev.koggles

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import java.util.concurrent.ConcurrentHashMap
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
 * as a KogglesSweep line. It asserts nothing; it measures which presets compile on this device.
 * Skipped when no sets are present.
 */
@RunWith(AndroidJUnit4::class)
class PresetCompileSweepTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val commands get() = AppGraph.renderCommands
    private val outcomes = ConcurrentHashMap<Long, String>()
    private var previous: NativeListener? = null

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
        previous?.let { NativeBridge.listener = it }
        runBlocking { AppGraph.settings.update { it.copy(locked = false) } }
        FakeAudio.uninstall()
    }

    private fun shell(cmd: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }

    private fun waitFor(timeoutMs: Long, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (ok()) return true; Thread.sleep(20) }
        return false
    }

    @Test fun sweep() {
        val sets = String(shell("ls $DIR")).lines().map { it.trim() }.filter { it.endsWith(".tsv") }
        assumeTrue("no sweep sets in $DIR", sets.isNotEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            check(waitFor(20_000) {
                val s = AppGraph.audio.snapshot.value.state
                if (s == AudioState.ShowingExplainer) inst.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                s == AudioState.Active && commands.attachedHandle != 0L
            }) { "app not ready" }
            check(waitFor(30_000) { AppGraph.libraryRepo.state.value.bundledTextureDir != null }) { "textures not ready" }
            val textures = listOf(AppGraph.libraryRepo.state.value.bundledTextureDir!!.absolutePath)
            previous = NativeBridge.listener
            NativeBridge.listener = object : NativeListener {
                override fun onSwitchRequested(hardCut: Boolean, requestId: Long) = Unit
                override fun onPresetFailed(requestId: Long, message: String) { outcomes[requestId] = "failed\t" + message.replace('\n', ' ').replace('\t', ' ') }
                override fun onPresetLoaded(requestId: Long) { outcomes[requestId] = "loaded\t" }
            }
            for (set in sets) {
                val lines = String(shell("cat $DIR/$set")).lines().filter { it.contains('\t') }
                var loaded = 0; var failed = 0; var timedOut = 0
                for (line in lines) {
                    val (name, b64) = line.split('\t', limit = 2)
                    val data = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) // as readPresetText does
                    val id = (commands.state.value.preset?.requestId ?: 0L) + 1_000
                    val t0 = System.currentTimeMillis()
                    commands.loadPreset(data, id, false, textures)
                    val done = waitFor(30_000) { outcomes.containsKey(id) }
                    val outcome = if (done) outcomes.getValue(id) else "timeout\t"
                    when (outcome.substringBefore('\t')) { "loaded" -> loaded++; "failed" -> failed++; else -> timedOut++ }
                    Log.i(TAG, "set=$set\t${outcome.substringBefore('\t')}\tms=${System.currentTimeMillis() - t0}\t$name\t${outcome.substringAfter('\t').take(300)}")
                }
                Log.i(TAG, "SUMMARY set=$set total=${lines.size} loaded=$loaded failed=$failed timeout=$timedOut")
            }
        }
    }
}
