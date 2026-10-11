package dev.koggles.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.koggles.AppGraph
import dev.koggles.NativeBridge
import dev.koggles.audio.AudioEvent
import dev.koggles.presets.AndroidAssetSource
import dev.koggles.presets.TextureInstaller
import dev.koggles.render.VisualizerView
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only rendering-load gate (G4). Extras: `goggles` (Boolean), `collision` (Boolean).
 * Normal mode cycles the asset paths in `filesDir/g4/g4-presets.txt` every 15 s with soft
 * blends and logs frame stats every 10 s (tag KogglesG4). Collision mode blends between
 * `filesDir/g4/packA/a.milk` and `packB/b.milk`, each with its own texture search path.
 */
class LoadGateActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    /** False once this gate stops; nothing it scheduled may load presets afterwards. */
    @Volatile private var active = true
    private lateinit var view: VisualizerView
    private val commands get() = AppGraph.renderCommands

    /** Request ids must stay above whatever RenderCommands (process-scoped) has accepted. */
    private fun nextRequestId(): Long = (commands.state.value.preset?.requestId ?: 0L) + 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = VisualizerView(this)
        setContentView(view)
        val goggles = intent.getBooleanExtra("goggles", false)
        commands.setGoggles(goggles)
        try {
            if (intent.getBooleanExtra("collision", false)) runCollision() else runCycle(goggles)
        } catch (e: java.io.IOException) {
            // Inputs live in filesDir/g4 (adb-pushed files under Android/data are unreadable on API 37);
            // see docs/verification/G4-rendering.md for the setup commands.
            Log.e(TAG, "missing G4 inputs in ${File(filesDir, "g4")}: ${e.message}")
            finish()
        }
        main.postDelayed(::logStats, 10_000)
    }

    private fun runCycle(goggles: Boolean) {
        val files = File(filesDir, "g4")
        val paths = File(files, "g4-presets.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) throw java.io.IOException("g4-presets.txt lists no presets")
        // Read every preset up front: missing inputs fail here, and no deferred I/O can throw later.
        val presets = paths.map { path -> path to assets.open(path).bufferedReader().use { it.readText() } }
        thread {
            val bundledTextures = TextureInstaller(
                AndroidAssetSource(assets),
                filesDir,
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime,
            ).installIfNeeded()
            main.post {
                if (!active) return@post
                if (!bundledTextures.isDirectory) {
                    // projectM would substitute placeholder textures: a different workload, so no run.
                    Log.e(TAG, "invalid gate: bundled textures unavailable at $bundledTextures")
                    active = false
                    main.removeCallbacksAndMessages(null)
                    finish()
                    return@post
                }
                val texturePaths = listOf(bundledTextures.absolutePath)
                Log.i(TAG, "start mode=${if (goggles) "goggles" else "normal"} presets=${presets.size} textures=$bundledTextures")
                var i = 0
                fun step() {
                    if (!active) return
                    val (path, data) = presets[i % presets.size]
                    commands.loadPreset(data, nextRequestId(), true, texturePaths)
                    Log.i(TAG, "preset ${i % presets.size}: $path")
                    i++
                    main.postDelayed(::step, 15_000)
                }
                step()
            }
        }
    }

    private fun runCollision() {
        val files = File(filesDir, "g4")
        val a = File(files, "packA")
        val b = File(files, "packB")
        val aData = File(a, "a.milk").readText()
        val bData = File(b, "b.milk").readText()
        fun load(dir: File, data: String, smooth: Boolean) {
            if (active) commands.loadPreset(data, nextRequestId(), smooth, listOf(dir.absolutePath))
        }
        Log.i(TAG, "phase=A")
        load(a, aData, false)
        main.postDelayed({ Log.i(TAG, "phase=blend-A-to-B"); load(b, bData, true) }, 5_000)
        main.postDelayed({ Log.i(TAG, "phase=B") }, 8_000)
        main.postDelayed({ Log.i(TAG, "phase=blend-B-to-A"); load(a, aData, true) }, 13_000)
        main.postDelayed({ Log.i(TAG, "phase=A-again") }, 16_000)
    }

    private fun logStats() {
        if (!active) return
        view.queueEvent {
            val h = commands.handleForCurrentThread()
            if (h != 0L) {
                val s = NativeBridge.rendererFrameStats(h)
                val thermal = getSystemService(PowerManager::class.java).currentThermalStatus
                Log.i(TAG, "p50=%.2f p95=%.2f n=%d thermal=%d".format(s[0], s[1], s[2].toInt(), thermal))
            }
        }
        main.postDelayed(::logStats, 10_000)
    }

    /** A new `am start` while a gate is running: end this run and start a fresh gate with the new extras. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        active = false
        main.removeCallbacksAndMessages(null)
        finish()
        startActivity(android.content.Intent(intent).setClass(this, LoadGateActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        AppGraph.audio.dispatch(AudioEvent.ActivityStarted(granted))
    }

    override fun onStop() {
        // Only the visible gate may drive the process-wide renderer: a stopped gate ends its run.
        active = false
        main.removeCallbacksAndMessages(null)
        AppGraph.audio.dispatch(AudioEvent.ActivityStopped)
        super.onStop()
        finish()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }

    override fun onPause() {
        view.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "KogglesG4"
    }
}
