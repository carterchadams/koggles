package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads presets one at a time through the real renderer and reports projectM's verdict. In
 * diagnostics builds (-PkogglesShaderDiagnostics) it also reads projectM's own preset messages,
 * because projectM 4.1.7 swallows warp and composite shader failures (it drops the warp shader or
 * substitutes the default composite shader) without reporting a failed load.
 *
 * Its loads bypass the playback controller and push the renderer's request ids far above the
 * controller's own counter, so for the rest of the process the controller's loads count as superseded.
 * Tests that need real playback (e.g. AutoAdvanceTest) must not run after a probe in the same
 * instrumentation process. The full suite's alphabetical order satisfies this, as it already did for
 * PresetCallbackTest's direct loads; run such tests separately in targeted runs.
 */
class PresetProbe private constructor(private val textures: List<String>) : AutoCloseable {

    data class Result(val outcome: String, val ms: Long, val message: String, val diagnostics: String, val diagnosticsOn: Boolean) {
        val shaderClass: String get() = classify(outcome, diagnostics, diagnosticsOn)
        /** The first projectM error line, for logs. */
        val firstError: String get() = diagnostics.lineSequence().firstOrNull { "Error" in it || it.startsWith("KOGGLES-DIAG") }.orEmpty()
    }

    private val commands get() = AppGraph.renderCommands
    private val outcomes = ConcurrentHashMap<Long, String>()
    private val previous = NativeBridge.listener

    init {
        NativeBridge.listener = object : NativeListener {
            override fun onSwitchRequested(hardCut: Boolean, requestId: Long) = Unit
            override fun onPresetFailed(requestId: Long, message: String) { outcomes[requestId] = "failed\t" + message.replace('\n', ' ').replace('\t', ' ') }
            override fun onPresetLoaded(requestId: Long) { outcomes[requestId] = "loaded\t" }
        }
    }

    fun load(data: String, timeoutMs: Long = 30_000): Result {
        val diagnosticsOn = NativeBridge.diagnosticsEnabled()
        NativeBridge.takeDiagnostics() // drop anything written before this preset
        val id = (commands.state.value.preset?.requestId ?: 0L) + 1_000
        val t0 = System.currentTimeMillis()
        commands.loadPreset(data, id, false, textures)
        val done = waitFor(timeoutMs) { outcomes.containsKey(id) }
        val ms = System.currentTimeMillis() - t0
        val raw = if (done) outcomes.getValue(id) else "timeout\t"
        return Result(raw.substringBefore('\t'), ms, raw.substringAfter('\t'), NativeBridge.takeDiagnostics(), diagnosticsOn)
    }

    override fun close() { NativeBridge.listener = previous }

    companion object {
        const val OVERFLOW = "KOGGLES-DIAG-OVERFLOW"
        const val DRAIN_TIMEOUT = "KOGGLES-DIAG-DRAIN-TIMEOUT"
        /** projectM prints this once per preset load (diagnostics builds). */
        const val LOAD_LINE = "[Preset] Loading preset from stream."

        /** compiled | warp-fallback | composite-fallback | warp+composite-fallback | failed | unknown | loaded-unverified */
        fun classify(outcome: String, diagnostics: String, diagnosticsOn: Boolean): String {
            if (outcome != "loaded") return "failed"
            if (!diagnosticsOn) return "loaded-unverified"
            if (OVERFLOW in diagnostics || DRAIN_TIMEOUT in diagnostics) return "unknown"
            // Exactly one load in the window, or the messages can't be attributed to this preset.
            if (diagnostics.lines().count { it.trim() == LOAD_LINE } != 1) return "unknown"
            val warp = "[Warp Shader] Error" in diagnostics
            val comp = "[Composite Shader] Error" in diagnostics || "[Composite Shader] Using fallback shader" in diagnostics
            return when {
                warp && comp -> "warp+composite-fallback"
                warp -> "warp-fallback"
                comp -> "composite-fallback"
                else -> "compiled"
            }
        }

        /** Launches the app with audio active and the renderer attached, then runs [block] with a probe. */
        fun <T> withRenderer(block: (PresetProbe) -> T): T {
            val inst = InstrumentationRegistry.getInstrumentation()
            return ActivityScenario.launch(MainActivity::class.java).use {
                check(waitFor(20_000) {
                    val s = AppGraph.audio.snapshot.value.state
                    if (s == AudioState.ShowingExplainer) inst.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
                    s == AudioState.Active && AppGraph.renderCommands.attachedHandle != 0L
                }) { "app not ready" }
                check(waitFor(30_000) { AppGraph.libraryRepo.state.value.bundledTextureDir != null }) { "textures not ready" }
                // A newly attached renderer replays the last preset load; let it finish before probing,
                // so its messages can't land in the first probe's window.
                if (NativeBridge.diagnosticsEnabled()) {
                    var quiet = 0
                    val deadline = System.currentTimeMillis() + 15_000
                    while (quiet < 3 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(250)
                        quiet = if (NativeBridge.takeDiagnostics().isBlank()) quiet + 1 else 0
                    }
                } else {
                    Thread.sleep(1_000)
                }
                PresetProbe(listOf(AppGraph.libraryRepo.state.value.bundledTextureDir!!.absolutePath)).use(block)
            }
        }

        fun waitFor(timeoutMs: Long, ok: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) { if (ok()) return true; Thread.sleep(20) }
            return false
        }
    }
}
