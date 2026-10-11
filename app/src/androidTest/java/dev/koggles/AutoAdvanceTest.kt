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
 * Blueprint Task 19 Step 5 / roadmap R1.4, automated: with the default settings the real app
 * auto-advances every 30 s and plays `!` transition interstitials for about 4 s.
 *
 * Timing is taken when each load is *issued* to the renderer (RenderCommands state), which is what
 * the playback logic controls. The time until the renderer acknowledges a load includes preset
 * compile time, which on the emulator's software GPU is seconds and varies per preset; it is logged
 * for information (a G4 performance concern), not asserted.
 */
@RunWith(AndroidJUnit4::class)
class AutoAdvanceTest {

    private data class Loaded(val atMs: Long, val requestId: Long, val interstitial: Boolean)

    private val loads = Collections.synchronizedList(mutableListOf<Loaded>()) // issued loads
    private val ackLatencyMs = Collections.synchronizedList(mutableListOf<Long>())
    private val issuedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private var displaced: NativeListener? = null
    private val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    @Before fun recordLoads() {
        runBlocking {
            AppGraph.settings.update {
                it.copy(presetDurationSec = 30, interstitials = true, interstitialSec = 4, locked = false, softCut = true)
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
                issuedAt[requestId]?.let { ackLatencyMs += SystemClock.elapsedRealtime() - it }
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
                loads += Loaded(t, id, AppGraph.playback.nowPlaying.value.interstitial)
            }
        }
    }

    @After fun restore() {
        watcher.cancel()
        NativeBridge.listener = displaced
    }

    @Test fun defaultSettings_autoAdvanceEvery30s_interstitialAbout4s() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = SystemClock.elapsedRealtime() + 100_000
            var advance: Long? = null
            var interstitial: Long? = null
            while (SystemClock.elapsedRealtime() < deadline && (advance == null || interstitial == null)) {
                Thread.sleep(250)
                val seen = synchronized(loads) { loads.toList() }
                for ((a, b) in seen.zipWithNext()) {
                    // A normal preset that played out its duration, then whatever came next.
                    if (!a.interstitial && b.requestId == a.requestId + 1 && advance == null && seen.first() != a) {
                        advance = b.atMs - a.atMs
                    }
                    if (a.interstitial && !b.interstitial && b.requestId == a.requestId + 1) {
                        interstitial = b.atMs - a.atMs
                    }
                }
            }
            val adv = advance ?: fail("no auto-advance observed in 100 s: $loads") as Nothing
            val inter = interstitial ?: fail("no interstitial observed in 100 s: $loads") as Nothing
            android.util.Log.i("AutoAdvanceTest", "advanceMs=$adv interstitialMs=$inter ackLatencyMs=$ackLatencyMs")
            assertTrue("auto-advance ${adv} ms, expected 30 s ± 2 s", adv in 28_000..32_000)
            assertTrue("interstitial ${inter} ms, expected 4 s ± 1 s", inter in 3_000..5_000)
        }
    }
}
