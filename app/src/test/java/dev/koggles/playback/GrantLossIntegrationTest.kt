package dev.koggles.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.koggles.presets.DocNode
import dev.koggles.presets.DocTree
import dev.koggles.presets.FakeDocTree
import dev.koggles.presets.LibraryRepository
import dev.koggles.presets.PresetIndexer
import dev.koggles.presets.PresetLibrary
import dev.koggles.presets.TextureMirror
import dev.koggles.settings.Scope
import dev.koggles.settings.Settings
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R2.3/R2.4: the user folder disappears during playback. Real [LibraryRepository], [PlaybackController]
 * and [Playlist]; the grant probe takes 500 ms of virtual time. Playback must make positive progress
 * on bundled presets (acknowledged loads), never idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrantLossIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = scopes.forEach { it.cancel() }

    private class MemPrefs : DataStore<Preferences> {
        private val s = MutableStateFlow(emptyPreferences())
        override val data = s
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(s.value).also { s.value = it }
    }

    /** 100 user presets under `Mine/sub/`; every read throws once [lost]. */
    private class UserTree(private val inner: FakeDocTree) : DocTree by inner {
        @Volatile var lost = false
        override fun readText(node: DocNode): String {
            if (lost) throw IOException("document gone")
            return inner.readText(node)
        }
    }

    private class Result(val idle: Int, val bundledAcked: Int, val firstAckAfterProbeMs: Long, val nonBundledAfterLoss: Int)

    private fun TestScope.runLoss(scopeSetting: Scope): Result {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob()).also { scopes += it }
        val bundled = FakeDocTree((0 until 4).map { "Cream/p$it.milk" })
        val user = UserTree(FakeDocTree((0 until 100).map { "Mine/sub/u$it.milk" }))
        var probeDoneAt = -1L
        val repo = LibraryRepository(
            scope = scope,
            bundledTree = { bundled },
            installTextures = { tmp.newFolder() },
            userTree = {
                if (user.lost) { delay(500); probeDoneAt = currentTime; null } else user
            },
            mirror = TextureMirror(tmp.newFolder("cache"), { _, f -> f.writeBytes(byteArrayOf(1)) }),
            indexer = PresetIndexer(dispatcher),
            io = dispatcher,
        )
        val prefs = MemPrefs()
        val settings = Settings(prefs)
        var idle = 0
        val acked = mutableListOf<Pair<Long, String>>() // (virtual time, key) of acknowledged loads
        lateinit var controller: PlaybackController
        val keyById = HashMap<Long, String>()
        val sink = object : RenderSink {
            override fun load(data: String, requestId: Long, smooth: Boolean, texturePaths: List<String>) {
                keyById[requestId] = controller.nowPlaying.value.ref!!.key
                scope.launch { controller.onPresetLoaded(requestId); acked += currentTime to keyById.getValue(requestId) }
            }
            override fun loadIdle(requestId: Long) { idle++ }
        }
        controller = PlaybackController(
            scope = scope,
            playlist = Playlist(Random(7)),
            sink = sink,
            reader = { ref ->
                repo.state.value.catalog.pack(ref.packId)?.tree?.readText(ref.node) ?: throw IOException("pack gone")
            },
            texturePaths = { emptyList() },
            settings = settings,
            library = PresetLibrary(prefs),
            libraryState = repo.state,
            onUserReadFailure = { repo.revalidate() },
        )
        scope.launch { settings.update { it.copy(scope = scopeSetting, interstitials = false) } }
        advanceUntilIdle()
        assertTrue("user packs indexed", repo.state.value.catalog.pack("user:Mine") != null)
        controller.start(); advanceUntilIdle()

        // The folder disappears; the next pick is a user preset.
        user.lost = true
        val lossAt = acked.size
        controller.select("user:Mine/sub/u0.milk"); advanceUntilIdle()
        repeat(12) { controller.next(); advanceUntilIdle() }

        val after = acked.drop(lossAt)
        val firstBundled = after.firstOrNull { it.second.startsWith("bundled:") }
        return Result(
            idle = idle,
            bundledAcked = after.count { it.second.startsWith("bundled:") },
            firstAckAfterProbeMs = if (firstBundled == null || probeDoneAt < 0) Long.MAX_VALUE else firstBundled.first - probeDoneAt,
            nonBundledAfterLoss = after.count { !it.second.startsWith("bundled:") },
        ).also {
            assertFalse("user packs hidden", repo.state.value.catalog.packs.any { p -> p.id.startsWith("user:") })
        }
    }

    private fun Result.assertRecovered() {
        println("R2.3 metrics: idle=$idle firstBundledAckAfterProbeMs=$firstAckAfterProbeMs bundledAcked=$bundledAcked nonBundledAfterLoss=$nonBundledAfterLoss")
        assertEquals("idle requests", 0, idle)
        assertTrue("first bundled ack ${firstAckAfterProbeMs} ms after probe", firstAckAfterProbeMs in 0..1_000)
        assertTrue("acknowledged bundled advances: $bundledAcked", bundledAcked >= 10)
        assertEquals("non-bundled loads after loss", 0, nonBundledAfterLoss)
    }

    @Test fun allEnabled_delayedProbe_continuesOnBundled() = runTest { runLoss(Scope.AllEnabled).assertRecovered() }

    @Test fun userPackScope_grantLost_continuesOnBundled() = runTest { runLoss(Scope.PackOnly("user:Mine")).assertRecovered() }

    @Test fun userFolderScope_grantLost_continuesOnBundled() = runTest { runLoss(Scope.Folder("user:Mine", "sub")).assertRecovered() }
}
