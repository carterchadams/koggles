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
    private lateinit var view: VisualizerView
    private var nextRequestId = 1_000_000L
    private val commands get() = AppGraph.renderCommands

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = VisualizerView(this)
        setContentView(view)
        val goggles = intent.getBooleanExtra("goggles", false)
        commands.setGoggles(goggles)
        if (intent.getBooleanExtra("collision", false)) runCollision() else runCycle(goggles)
        main.postDelayed(::logStats, 10_000)
    }

    private fun runCycle(goggles: Boolean) {
        val files = File(filesDir, "g4")
        val presets = File(files, "g4-presets.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        thread {
            val bundledTextures = TextureInstaller(
                AndroidAssetSource(assets),
                filesDir,
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime,
            ).installIfNeeded()
            val paths = if (bundledTextures.exists()) listOf(bundledTextures.absolutePath) else emptyList()
            Log.i(TAG, "start mode=${if (goggles) "goggles" else "normal"} presets=${presets.size} textures=${bundledTextures.exists()}")
            var i = 0
            fun step() {
                val path = presets[i % presets.size]
                val data = assets.open(path).bufferedReader().use { it.readText() }
                commands.loadPreset(data, nextRequestId++, true, paths)
                Log.i(TAG, "preset ${i % presets.size}: $path")
                i++
                main.postDelayed(::step, 15_000)
            }
            main.post(::step)
        }
    }

    private fun runCollision() {
        val files = File(filesDir, "g4")
        fun load(pack: String, file: String, smooth: Boolean) {
            val dir = File(files, pack)
            commands.loadPreset(File(dir, file).readText(), nextRequestId++, smooth, listOf(dir.absolutePath))
        }
        Log.i(TAG, "phase=A")
        load("packA", "a.milk", false)
        main.postDelayed({ Log.i(TAG, "phase=blend-A-to-B"); load("packB", "b.milk", true) }, 5_000)
        main.postDelayed({ Log.i(TAG, "phase=B") }, 8_000)
        main.postDelayed({ Log.i(TAG, "phase=blend-B-to-A"); load("packA", "a.milk", true) }, 13_000)
        main.postDelayed({ Log.i(TAG, "phase=A-again") }, 16_000)
    }

    private fun logStats() {
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

    override fun onStart() {
        super.onStart()
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        AppGraph.audio.dispatch(AudioEvent.ActivityStarted(granted))
    }

    override fun onStop() {
        AppGraph.audio.dispatch(AudioEvent.ActivityStopped)
        super.onStop()
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
