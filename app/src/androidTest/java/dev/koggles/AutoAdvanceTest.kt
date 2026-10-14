package dev.koggles

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With the default settings the real app auto-advances every 30 s and plays `!` transition
 * never a transition preset.
 *
 * Timing is taken when each load is *issued* to the renderer (RenderCommands state), which is what
 * the playback logic controls. The time until the renderer acknowledges a load includes preset
 * compile time, which on the emulator's software GPU is seconds and varies per preset; it is logged
 * for information (a rendering-load concern, measured separately), not asserted.
 */
@RunWith(AndroidJUnit4::class)
class AutoAdvanceTest {

    private data class Loaded(val atMs: Long, val requestId: Long, val transition: Boolean)

    private val loads = Collections.synchronizedList(mutableListOf<Loaded>()) // issued loads
    private val ackLatencyMs = Collections.synchronizedList(mutableListOf<Long>())
    private val issuedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val ackedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private var displaced: NativeListener? = null
    private val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    @Before fun recordLoads() {
        runBlocking {
            AppGraph.settings.update {
                it.copy(presetDurationSec = 30, locked = false, softCut = true)
            }
        }
        val real = NativeBridge.listener
        displaced = real
        NativeBridge.listener = object : NativeListener {
            override fun onSwitchRequested(hardCut: Boolean, requestId: Long) {
                real?.onSwitchRequested(hardCut, requestId)
            }
            override fun onPresetFailed(requestId: Long, message: String) {
                real?.onPresetFailed(requestId, message)
            }
            override fun onPresetLoaded(requestId: Long) {
                val now = SystemClock.elapsedRealtime()
                ackedAt.putIfAbsent(requestId, now)
                issuedAt[requestId]?.let { ackLatencyMs += now - it }
                real?.onPresetLoaded(requestId)
            }
        }
        // Record each load as RenderCommands accepts it (main thread, same turn as the playback
        // controller issues it, so nowPlaying describes that request).
        watcher.launch {
            AppGraph.renderCommands.state.collect { st ->
                val id = st.preset?.requestId ?: return@collect
                if (issuedAt.containsKey(id) || id != AppGraph.playback.nowPlayingRequestId()) return@collect
                val t = SystemClock.elapsedRealtime()
                issuedAt[id] = t
                loads += Loaded(t, id, AppGraph.playback.nowPlaying.value.ref?.isTransition == true)
            }
        }
    }

    @After fun restore() {
        watcher.cancel()
        NativeBridge.listener = displaced
    }

    /** Default settings: an advance about every 30 s, and never a transition preset (the author removed interstitials). */
    @Test fun defaults_autoAdvanceEvery30s_andNeverATransition() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = SystemClock.elapsedRealtime() + 100_000
            var advance: Long? = null
            while (SystemClock.elapsedRealtime() < deadline && advance == null) {
                Thread.sleep(250)
                val seen = synchronized(loads) { loads.toList() }
                for ((a, b) in seen.zipWithNext()) {
                    // Skip the first load (it may have started under the previous settings); then a preset that
                    // played out its duration and whatever came next.
                    if (b.requestId == a.requestId + 1 && advance == null && seen.first() != a) advance = b.atMs - a.atMs
                }
            }
            val adv = advance ?: fail("no auto-advance observed in 100 s: $loads") as Nothing
            val transitions = synchronized(loads) { loads.filter { it.transition } }
            android.util.Log.i("AutoAdvanceTest", "advanceMs=$adv transitions=${transitions.size} ackLatencyMs=$ackLatencyMs")
            assertTrue("transition preset loaded: $transitions", transitions.isEmpty())
            assertTrue("auto-advance ${adv} ms, expected 30 s ± 2 s", adv in 28_000..32_000)
        }
    }

    /**
     * The auto-advance setting is a fixed interval, not an average. projectM randomises each preset's
     * duration around the setting unless told not to (its "easter egg" sigma, default 1 s), which
     * gave 27.45 s for "30 s" in one instrumented run. Four consecutive advances at 15 s must each land within
     * ±0.5 s; with the default sigma that happens ~2 % of the time.
     */
    @Test fun advanceInterval_isFixed_notRandomised() {
        runBlocking { AppGraph.settings.update { it.copy(presetDurationSec = 15) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val deadline = SystemClock.elapsedRealtime() + 110_000
                var intervals = emptyList<Long>()
                while (SystemClock.elapsedRealtime() < deadline && intervals.size < 4) {
                    Thread.sleep(250)
                    val seen = synchronized(loads) { loads.toList() }
                    // Skip the first load: its preset may have started under the previous settings.
                    intervals = seen.drop(1).zipWithNext()
                        .filter { (a, b) -> b.requestId == a.requestId + 1 }
                        .map { (a, b) -> b.atMs - a.atMs }
                }
                android.util.Log.i("AutoAdvanceTest", "fixed-interval samples=$intervals")
                assertTrue("need 4 advances, got $intervals", intervals.size >= 4)
                for (ms in intervals.take(4)) assertTrue("advance $ms ms, expected 15 s ± 0.5 s ($intervals)", ms in 14_500..15_500)
            }
        } finally {
            runBlocking { AppGraph.settings.update { it.copy(presetDurationSec = 30) } }
        }
    }

    /**
     * Another loader (the debug load-gate harness, a test probe, a future preview) can put its own
     * preset on screen with a newer request id. Playback must take control
     * back at the next switch request. It used to ignore every switch request carrying the foreign id,
     * and every load it issued below that id was superseded, so playback froze for the process.
     */
    @Test fun autoAdvance_resumesAfterAForeignLoad() {
        runBlocking { AppGraph.settings.update { it.copy(presetDurationSec = 15) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val start = SystemClock.elapsedRealtime() + 20_000
                while (SystemClock.elapsedRealtime() < start && synchronized(loads) { loads.isEmpty() }) Thread.sleep(100)
                assertTrue("no playback load at start", synchronized(loads) { loads.isNotEmpty() })
                val data = AppGraph.app.assets.open("g1/ok.milk").bufferedReader().use { it.readText() }
                val foreign = (AppGraph.renderCommands.state.value.preset?.requestId ?: 0L) + 1_000
                AppGraph.renderCommands.loadPreset(data, foreign, false, emptyList()) // as the debug load-gate harness does
                val deadline = SystemClock.elapsedRealtime() + 25_000 // one 15 s duration plus margin
                while (SystemClock.elapsedRealtime() < deadline && synchronized(loads) { loads.none { it.requestId > foreign } }) {
                    Thread.sleep(250)
                }
                val resumed = synchronized(loads) { loads.filter { it.requestId > foreign } }
                assertTrue("playback did not resume after a foreign load (id $foreign): $loads", resumed.isNotEmpty())
            }
        } finally {
            runBlocking { AppGraph.settings.update { it.copy(presetDurationSec = 30) } }
        }
    }
}
