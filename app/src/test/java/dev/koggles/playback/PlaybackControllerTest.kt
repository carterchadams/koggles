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
import kotlinx.coroutines.CoroutineScope
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

    private class FakeSink : RenderSink {
        val calls = mutableListOf<String>()
        override fun load(data: String, requestId: Long, smooth: Boolean, texturePaths: List<String>) {
            calls += "load($data,$requestId,$smooth,${texturePaths.joinToString()})"
        }
        override fun loadIdle(requestId: Long) { calls += "idle($requestId)" }
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
    private val reader = PresetReader { ref: PresetRef ->
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
    ): PlaybackController {
        // Not backgroundScope: advanceUntilIdle() does not drive background work.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return PlaybackController(scope, Playlist(Random(seed)), sink, reader, texturePaths, settings, library, libState)
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
        c.select(target); advanceUntilIdle()
        c.onSwitchRequested(false, shown); advanceUntilIdle()
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
        advanceTimeBy(4_100); runCurrent()
        assertFalse(loadedKey(sink.loads().last()).contains("!t/"))
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
