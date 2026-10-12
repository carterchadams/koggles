package dev.koggles.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.koggles.presets.Catalog
import dev.koggles.presets.LibraryState
import dev.koggles.presets.PresetLibrary
import dev.koggles.presets.PresetRef
import dev.koggles.settings.Settings
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private class MemPrefs : DataStore<Preferences> {
        private val s = MutableStateFlow(emptyPreferences())
        /** When set, every write suspends until it completes. */
        var writeGate: CompletableDeferred<Unit>? = null
        override val data = s
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writeGate?.await()
            return transform(s.value).also { s.value = it }
        }
    }

    /** Models RenderCommands: a load below the current request is dropped (superseded). */
    private class FakeSink : RenderSink {
        val calls = mutableListOf<String>()
        var current = 0L
        override val currentRequestId: Long get() = current
        override fun load(data: String, requestId: Long, smooth: Boolean, texturePaths: List<String>) {
            if (requestId < current) { calls += "superseded($requestId)"; return }
            current = requestId
            calls += "load($data,$requestId,$smooth,${texturePaths.joinToString()})"
        }
        override fun loadIdle(requestId: Long) {
            if (requestId < current) { calls += "superseded($requestId)"; return }
            current = requestId
            calls += "idle($requestId)"
        }
        fun loads() = calls.filter { it.startsWith("load(") }
    }

    private val catalog = flatCatalog(4) // bundled:p/p0..p3.milk
    private val keys = keysOf(catalog)
    private val prefs = MemPrefs()
    private val settings = Settings(prefs)
    private val library = PresetLibrary(prefs)
    private val sink = FakeSink()
    private val gates = HashMap<String, CompletableDeferred<Unit>>()
    private val failing = HashSet<String>()
    /** Keys whose read blocks a real thread (a provider that never answers), until [blockRelease]. */
    private val blocking = HashSet<String>()
    private val blockRelease = java.util.concurrent.CountDownLatch(1)
    @After fun releaseBlockedReads() = blockRelease.countDown()

    private val reader = PresetReader { ref: PresetRef ->
        if (ref.key in blocking) withContext(Dispatchers.IO) { blockRelease.await() }
        gates[ref.key]?.await()
        if (ref.key in failing) throw IOException("unreadable ${ref.key}")
        "data:${ref.key}"
    }
    private val libState = MutableStateFlow(state(ready = true))

    private fun state(
        ready: Boolean, cat: Catalog = catalog, indexing: Boolean = false, grant: Boolean = false, probed: Boolean = true,
    ) = LibraryState(cat, ready, indexing, grant, null, emptyMap(), emptyList(), userProbed = probed)

    private fun TestScope.controller(
        texturePaths: suspend (String) -> List<String> = { listOf("/tex/$it") },
        seed: Int = 1,
        onUserReadFailure: suspend () -> Unit = {},
    ): PlaybackController {
        // Not backgroundScope: advanceUntilIdle() does not drive background work.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return PlaybackController(scope, Playlist(Random(seed)) { sink.currentRequestId }, sink, reader, texturePaths, settings, library, libState, onUserReadFailure)
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun loadedKey(call: String) = call.substringAfter("load(data:").substringBefore(",")

    @Test fun start_waitsForBundled() = runTest {
        libState.value = state(ready = false, cat = Catalog(emptyList()))
        val c = controller()
        c.start(); advanceUntilIdle()
        assertTrue(sink.calls.isEmpty())
        libState.value = state(ready = true); advanceUntilIdle()
        assertEquals(1, sink.loads().size)
    }

    @Test fun start_noSinkCallUntilBundledReady() = runTest {
        libState.value = state(ready = false)
        val c = controller()
        c.start(); advanceTimeBy(60_000)
        assertTrue(sink.calls.isEmpty())
        libState.value = state(ready = true); advanceUntilIdle()
        assertEquals(1, sink.calls.size)
    }

    @Test fun start_userLastKey_waitsForUserIndexUpTo3s() = runTest {
        val userCat = catalogOf(pack("bundled:p", "p0.milk"), pack("user:u", "u0.milk"))
        settings.update { it.copy(lastPresetKey = "user:u/u0.milk") }
        libState.value = state(ready = true, cat = catalogOf(pack("bundled:p", "p0.milk")), indexing = true, grant = true)
        val c = controller()
        c.start(); advanceTimeBy(1_000)
        assertTrue(sink.calls.isEmpty())
        libState.value = state(ready = true, cat = userCat, indexing = false, grant = true); advanceUntilIdle()
        assertEquals("user:u/u0.milk", loadedKey(sink.loads().single()))
    }

    @Test fun start_userLastKey_timeoutFallsBack() = runTest {
        settings.update { it.copy(lastPresetKey = "user:u/u0.milk") }
        libState.value = state(ready = true, indexing = true, grant = true)
        val c = controller()
        c.start(); advanceTimeBy(3_100); runCurrent()
        assertEquals(1, sink.loads().size)
        assertTrue(loadedKey(sink.loads().single()) in keys)
    }

    @Test fun start_grantProbedAfterBundledReady_stillRestoresUserKey() = runTest {
        // Production order: bundled readiness is published before the grant probe completes.
        val bundledOnly = catalogOf(pack("bundled:p", "p0.milk"))
        settings.update { it.copy(lastPresetKey = "user:u/u0.milk") }
        libState.value = state(ready = true, cat = bundledOnly, grant = false, probed = false)
        val c = controller()
        c.start(); advanceTimeBy(500)
        assertTrue(sink.calls.isEmpty())
        libState.value = state(ready = true, cat = bundledOnly, indexing = true, grant = true)
        advanceTimeBy(500)
        assertTrue(sink.calls.isEmpty())
        libState.value = state(ready = true, cat = catalogOf(pack("bundled:p", "p0.milk"), pack("user:u", "u0.milk")), grant = true)
        advanceUntilIdle()
        assertEquals("user:u/u0.milk", loadedKey(sink.loads().single()))
    }

    @Test fun start_userNavigatesDuringWait_startupDoesNotOverride() = runTest {
        settings.update { it.copy(lastPresetKey = "user:u/u0.milk") }
        libState.value = state(ready = true, probed = false)
        val c = controller()
        c.start(); advanceTimeBy(500)
        c.select(keys[2]); runCurrent()
        advanceTimeBy(3_100); advanceUntilIdle()
        assertEquals(keys[2], loadedKey(sink.loads().single()))
    }

    @Test fun start_navigationBeforeBundledReady_startupDoesNotOverride() = runTest {
        libState.value = state(ready = false)
        val c = controller()
        c.start(); runCurrent()
        c.select(keys[2]); advanceUntilIdle()
        libState.value = state(ready = true); advanceUntilIdle()
        assertEquals(keys[2], loadedKey(sink.loads().single()))
    }

    @Test fun skip_navigationDuringPersist_keepsNewerPlayback() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val a = c.nowPlaying.value.ref!!.key
        prefs.writeGate = CompletableDeferred()
        val skipping = launch { c.skip() }
        runCurrent()
        c.next(); advanceUntilIdle()
        val b = c.nowPlaying.value.ref!!.key
        prefs.writeGate!!.complete(Unit); skipping.join(); advanceUntilIdle()
        assertTrue(a in library.skipped.first())
        assertEquals(b, c.nowPlaying.value.ref!!.key)
        assertEquals(2, sink.loads().size)
    }

    @Test fun replayedLoadedOutcome_doesNotFloodHistory() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val a = c.nowPlaying.value.ref!!.key
        c.onPresetLoaded(c.nowPlayingRequestId())
        c.next(); advanceUntilIdle()
        // Renderer recreation replays B with its original id, many times.
        repeat(150) { c.onPresetLoaded(c.nowPlayingRequestId()) }
        advanceUntilIdle()
        c.previous(); advanceUntilIdle()
        assertEquals(a, c.nowPlaying.value.ref!!.key)
    }

    @Test fun userReadFailure_awaitsHookBeforeNextPick() = runTest {
        val withUser = catalogOf(pack("bundled:p", "p0.milk", "p1.milk"), pack("user:u", "u0.milk"))
        libState.value = state(ready = true, cat = withUser, grant = true)
        failing += "user:u/u0.milk"
        settings.update { it.copy(lastPresetKey = "bundled:p/p0.milk") } // start on a bundled preset
        val hook = CompletableDeferred<Unit>()
        var hookCalls = 0
        val c = controller(onUserReadFailure = {
            hookCalls++
            hook.await()
            libState.value = state(ready = true, cat = catalogOf(pack("bundled:p", "p0.milk", "p1.milk")))
        })
        c.start(); advanceUntilIdle()
        val before = sink.loads().size
        c.select("user:u/u0.milk"); runCurrent() // revalidation in flight (not stalled past the deadline)
        assertEquals(1, hookCalls)
        assertEquals("no pick while the library is being revalidated", before, sink.loads().size)
        hook.complete(Unit); advanceUntilIdle()
        assertEquals(before + 1, sink.loads().size)
        assertTrue(loadedKey(sink.loads().last()).startsWith("bundled:"))
    }

    @Test fun bundledReadFailure_doesNotInvokeHook() = runTest {
        failing += keys[0]
        var hookCalls = 0
        val c = controller(onUserReadFailure = { hookCalls++ })
        c.start(); advanceUntilIdle()
        c.select(keys[0]); advanceUntilIdle()
        assertEquals(0, hookCalls)
    }

    @Test fun switchRequested_staleRequestIgnored() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.onSwitchRequested(false, c.nowPlayingRequestId() - 1); advanceUntilIdle()
        assertEquals(1, sink.loads().size)
    }

    @Test fun switchRequested_duringUserSelection_ignored() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val shown = c.nowPlayingRequestId()
        c.onPresetLoaded(shown)
        val target = keys.first { it != loadedKey(sink.loads().single()) }
        gates[target] = CompletableDeferred()
        c.select(target); runCurrent() // the read is in flight (not stalled past the load timeout)
        c.onSwitchRequested(false, shown); runCurrent()
        gates[target]!!.complete(Unit); advanceUntilIdle()
        assertEquals(2, sink.loads().size)
        assertEquals(target, loadedKey(sink.loads().last()))
    }

    @Test fun switchRequested_whenLocked_ignored() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.setLocked(true); advanceUntilIdle()
        c.onSwitchRequested(false, c.nowPlayingRequestId()); advanceUntilIdle()
        assertEquals(1, sink.loads().size)
    }

    @Test fun switchRequested_advances() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.onSwitchRequested(false, c.nowPlayingRequestId()); advanceUntilIdle()
        assertEquals(2, sink.loads().size)
    }

    private fun idOf(call: String) = call.substringAfterLast("load(").split(",")[1].toLong()

    /** Q1: after another loader's request, the controller's next load is above it (not superseded). */
    @Test fun afterAForeignLoad_nextLoadIsAboveTheRenderersRequest() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val foreign = c.nowPlayingRequestId() + 1_000
        sink.current = foreign // a foreign load was accepted by the renderer
        c.next(); advanceUntilIdle()
        assertTrue("next load must be above $foreign: ${sink.calls}", idOf(sink.loads().last()) > foreign)
    }

    /**
     * Our load is pending (read in flight) when a higher-id foreign load is accepted and
     * fails. Our pending load is then superseded and the renderer keeps showing our older preset, whose
     * switch callbacks are stale. Playback must still recover, reloading our latest target above the
     * foreign id, without manual navigation.
     */
    @Test fun failedForeignLoad_whileOwnLoadPending_reissuesLatestAboveFloor() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val shown = c.nowPlayingRequestId()
        c.onPresetLoaded(shown)
        val target = keys.first { it != loadedKey(sink.loads().single()) }
        gates[target] = CompletableDeferred()
        c.select(target); runCurrent() // our latest: its read is in flight (not stalled past the load timeout)
        val foreign = c.nowPlayingRequestId() + 1_000
        sink.current = foreign // accepted ...
        c.onPresetFailed(foreign, "foreign preset failed"); runCurrent() // ... and failed
        gates[target]!!.complete(Unit); advanceUntilIdle()
        c.onSwitchRequested(false, shown); advanceUntilIdle() // the older displayed id: stale
        val last = sink.loads().last()
        assertEquals(target, loadedKey(last))
        assertTrue("reloaded above $foreign: ${sink.calls}", idOf(last) > foreign)
    }

    /** A newer id on screen came from another loader; take control back instead of freezing. */
    @Test fun switchRequestForAForeignPreset_takesControlAndAdvances() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.onSwitchRequested(false, c.nowPlayingRequestId() + 1_000); advanceUntilIdle()
        assertEquals(2, sink.loads().size)
    }

    @Test fun switchRequestForAForeignPreset_whenLocked_ignored() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.setLocked(true); advanceUntilIdle()
        c.onSwitchRequested(false, c.nowPlayingRequestId() + 1_000); advanceUntilIdle()
        assertEquals(1, sink.loads().size)
    }

    @Test fun hardCutRequested_loadsWithSmoothFalse() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.onSwitchRequested(true, c.nowPlayingRequestId()); advanceUntilIdle()
        assertTrue(sink.loads().last().contains(",false,"))
        c.next(); advanceUntilIdle()
        assertTrue("hard cut applies to one load only", sink.loads().last().contains(",true,"))
    }

    @Test fun interstitialTimerElapses_loadsDestination() = runTest {
        val mixed = catalogOf(pack("bundled:a", "p0.milk", "p1.milk", "!t/t0.milk"))
        libState.value = state(ready = true, cat = mixed)
        val c = controller(); c.start(); advanceUntilIdle()
        c.next(); runCurrent()
        assertTrue(loadedKey(sink.loads().last()).contains("!t/"))
        assertTrue(c.nowPlaying.value.interstitial)
        c.onPresetLoaded(c.nowPlayingRequestId()) // on screen now
        advanceTimeBy(4_100); runCurrent()
        assertFalse(loadedKey(sink.loads().last()).contains("!t/"))
    }

    /** A preset read that never completes (a stalled cloud provider) must not stall playback. */
    @Test fun stalledRead_timesOut_andPlaybackMovesOn() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val target = keys.first { it != loadedKey(sink.loads().single()) }
        gates[target] = CompletableDeferred() // never completed
        c.select(target); runCurrent()
        advanceTimeBy(PlaybackController.LOAD_TIMEOUT_MS + 100); runCurrent()
        val last = sink.loads().last()
        assertEquals(2, sink.loads().size)
        assertTrue("moved on from the stalled preset: $last", loadedKey(last) != target)
    }

    /**
     * Cancellation can't interrupt a blocking content-provider read, so the deadline must not wait for
     * the read to return: playback moves on at the deadline while the read is still stuck.
     */
    @Test fun blockingRead_doesNotHoldPlaybackPastTheDeadline() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val target = keys.first { it != loadedKey(sink.loads().single()) }
        blocking += target
        c.select(target); runCurrent()
        advanceTimeBy(PlaybackController.LOAD_TIMEOUT_MS + 100); runCurrent()
        assertEquals(2, sink.loads().size)
        assertTrue(loadedKey(sink.loads().last()) != target)
    }

    /** A stalled folder revalidation after a user-pack read failure must not hold playback either. */
    @Test fun stalledRevalidation_doesNotHoldPlaybackPastTheDeadline() = runTest {
        val withUser = catalogOf(pack("bundled:p", "p0.milk", "p1.milk"), pack("user:u", "u0.milk"))
        libState.value = state(ready = true, cat = withUser, grant = true)
        failing += "user:u/u0.milk"
        settings.update { it.copy(lastPresetKey = "bundled:p/p0.milk") }
        val c = controller(onUserReadFailure = { withContext(Dispatchers.IO) { blockRelease.await() } })
        c.start(); advanceUntilIdle()
        val before = sink.loads().size
        c.select("user:u/u0.milk"); runCurrent()
        advanceTimeBy(PlaybackController.LOAD_TIMEOUT_MS + 100); runCurrent()
        assertEquals(before + 1, sink.loads().size)
        assertTrue(loadedKey(sink.loads().last()).startsWith("bundled:"))
    }

    /**
     * Abandoning the wait must not cancel the work: a revalidation cancelled mid-way (it has a
     * suspension point between changing its generation and publishing) leaves the library half
     * updated. The hook must run to completion after playback has moved on.
     */
    @Test fun stalledRevalidation_isAbandonedNotCancelled() = runTest {
        val withUser = catalogOf(pack("bundled:p", "p0.milk", "p1.milk"), pack("user:u", "u0.milk"))
        libState.value = state(ready = true, cat = withUser, grant = true)
        failing += "user:u/u0.milk"
        settings.update { it.copy(lastPresetKey = "bundled:p/p0.milk") }
        var completed = false
        var cancelled = false
        val c = controller(onUserReadFailure = {
            try {
                withContext(Dispatchers.IO) { blockRelease.await() }
                completed = true
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        })
        c.start(); advanceUntilIdle()
        c.select("user:u/u0.milk"); runCurrent()
        advanceTimeBy(PlaybackController.LOAD_TIMEOUT_MS + 100); runCurrent()
        assertTrue("playback moved on", loadedKey(sink.loads().last()).startsWith("bundled:"))
        blockRelease.countDown()
        // The blocked IO thread wakes in real time; give it a moment to resume onto the test scheduler.
        repeat(100) { if (completed || cancelled) return@repeat; Thread.sleep(20); advanceUntilIdle() }
        assertFalse("the revalidation was cancelled mid-way", cancelled)
        assertTrue("the revalidation ran to completion", completed)
    }

    /** The interstitial's visible duration starts when it is on screen, not when its load was issued. */
    @Test fun interstitialTimer_startsAtLoadedAck_notAtIssue() = runTest {
        val mixed = catalogOf(pack("bundled:a", "p0.milk", "p1.milk", "!t/t0.milk"))
        libState.value = state(ready = true, cat = mixed)
        val c = controller(); c.start(); advanceUntilIdle()
        gates["bundled:a/!t/t0.milk"] = CompletableDeferred()
        c.next(); runCurrent()
        assertTrue(c.nowPlaying.value.interstitial)
        advanceTimeBy(8_000); runCurrent() // slow load: the interstitial is not on screen yet
        assertTrue("destination issued before the interstitial was shown", c.nowPlaying.value.interstitial)
        gates["bundled:a/!t/t0.milk"]!!.complete(Unit); runCurrent()
        val shown = c.nowPlayingRequestId()
        c.onPresetLoaded(shown)
        advanceTimeBy(3_900); runCurrent()
        assertTrue(c.nowPlaying.value.interstitial)
        advanceTimeBy(200); runCurrent()
        assertFalse(c.nowPlaying.value.interstitial)
    }

    @Test fun navigationCancelsInterstitialTimer() = runTest {
        val mixed = catalogOf(pack("bundled:a", "p0.milk", "p1.milk", "p2.milk", "!t/t0.milk"))
        libState.value = state(ready = true, cat = mixed)
        val c = controller(); c.start(); advanceUntilIdle()
        c.next(); runCurrent()
        c.select("bundled:a/p2.milk"); runCurrent()
        val count = sink.loads().size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(count, sink.loads().size)
    }

    @Test fun readThrows_treatedAsFailure() = runTest {
        failing += keys.toSet() - keys.first()
        val c = controller(); c.start(); advanceUntilIdle()
        repeat(3) { c.next(); advanceUntilIdle() }
        assertTrue(sink.loads().all { loadedKey(it) == keys.first() })
    }

    @Test fun failedCallback_staleIgnored() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val before = sink.calls.size
        c.onPresetFailed(requestId = 0L, message = "old")
        advanceUntilIdle()
        assertEquals(before, sink.calls.size)
    }

    @Test fun loaded_persistsLastKey_normalOnly() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val id = c.nowPlayingRequestId()
        c.onPresetLoaded(id); advanceUntilIdle()
        assertEquals(c.nowPlaying.value.ref?.key, settings.values.first().lastPresetKey)
    }

    @Test fun skip_persistsSkipAndAdvances() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val skippedKey = c.nowPlaying.value.ref!!.key
        c.skip(); advanceUntilIdle()
        assertTrue(skippedKey in library.skipped.first())
        assertEquals(2, sink.loads().size)
        assertFalse(loadedKey(sink.loads().last()) == skippedKey)
    }

    @Test fun texturePathsComeFromRepo() = runTest {
        val c = controller(texturePaths = { listOf("/own/$it", "/bundled") }); c.start(); advanceUntilIdle()
        assertTrue(sink.loads().single().endsWith(",/own/bundled:p, /bundled)"))
    }

    @Test fun idleRequest_callsLoadIdle_andExposesNotice() = runTest {
        libState.value = state(ready = true, cat = Catalog(emptyList()))
        val c = controller(); c.start(); advanceUntilIdle()
        assertTrue(sink.calls.single().startsWith("idle("))
        assertEquals(EMPTY_SCOPE_NOTICE, c.nowPlaying.value.notice)
    }

    @Test fun slowReadOfA_completingAfterB_neverReachesSink() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        sink.calls.clear()
        val a = keys[1]
        gates[a] = CompletableDeferred()
        c.select(a); runCurrent()
        c.select(keys[2]); advanceUntilIdle()
        gates.getValue(a).complete(Unit); advanceUntilIdle()
        assertEquals(listOf(keys[2]), sink.loads().map(::loadedKey))
    }

    @Test fun slowTexturePaths_supersededDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        var gateFirst = false
        val c = controller(texturePaths = { if (gateFirst) { gateFirst = false; gate.await() }; listOf("/t") })
        c.start(); advanceUntilIdle(); sink.calls.clear()
        gateFirst = true
        c.select(keys[1]); runCurrent()
        c.select(keys[2]); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(keys[2]), sink.loads().map(::loadedKey))
    }

    @Test fun supersededLoad_emitsNoPlaylistCallback() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        val a = keys[1]
        gates[a] = CompletableDeferred(); failing += a
        c.select(a); runCurrent()
        c.select(keys[2]); advanceUntilIdle()
        val latest = c.nowPlayingRequestId()
        gates.getValue(a).complete(Unit); advanceUntilIdle()
        assertEquals("a superseded failure issues no new request", latest, c.nowPlayingRequestId())
    }
}
