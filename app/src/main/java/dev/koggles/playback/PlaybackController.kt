package dev.koggles.playback

import dev.koggles.NativeListener
import dev.koggles.presets.LibraryState
import dev.koggles.presets.PresetLibrary
import dev.koggles.presets.PresetRef
import dev.koggles.settings.Settings
import dev.koggles.settings.SettingsValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where loads go; [dev.koggles.render.RenderCommands] in production. */
interface RenderSink {
    /**
     * The request the renderer treats as current; loads with a lower id are dropped. 0 if none. Read
     * on the main thread, like every other playback call (projectM's upcalls are re-posted to main).
     */
    val currentRequestId: Long
    fun load(data: String, requestId: Long, smooth: Boolean, texturePaths: List<String>)
    fun loadIdle(requestId: Long)
}

/** Reads a preset's text from the tree that indexed it. */
fun interface PresetReader {
    suspend fun read(ref: PresetRef): String
}

data class NowPlaying(val ref: PresetRef? = null, val notice: String? = null, val interstitial: Boolean = false)

/**
 * Drives [Playlist] from user actions, projectM events and timers, and turns its requests into
 * loads. Main thread only (the [scope] must use the main dispatcher in production).
 *
 * A request superseded while its preset text or texture paths were being fetched never reaches
 * the sink, and its outcome never reaches the playlist: a slow read finishing late must not
 * replace a newer preset or count as its failure.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val playlist: Playlist,
    private val sink: RenderSink,
    private val reader: PresetReader,
    private val texturePaths: suspend (String) -> List<String>,
    private val settings: Settings,
    private val library: PresetLibrary,
    private val libraryState: StateFlow<LibraryState>,
    /** Awaited when a `user:` preset can't be read: re-checks the folder grant, since the folder may be gone. */
    private val onUserReadFailure: suspend () -> Unit = {},
) : NativeListener, PlaybackControls {

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    override val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private var values = SettingsValues()
    private var lastIssued: PlayRequest? = null
    private var loadJob: Job? = null
    private var timerJob: Job? = null
    private var hardCutPending = false

    /** The library state most recently applied to the playlist (null until the first update). */
    private val applied = MutableStateFlow<LibraryState?>(null)

    private var lastLoadedId = -1L

    init {
        scope.launch {
            combine(libraryState, library.disabledPacks, library.favorites, library.skipped, settings.values) {
                    state, disabled, favorites, skipped, v -> Inputs(state, disabled, favorites, skipped, v)
            }.collect { i ->
                values = i.values
                playlist.update(
                    PlaylistInputs(i.state.catalog, i.disabled, i.favorites, i.skipped, i.values.scope, i.values.interstitials),
                )
                applied.value = i.state
            }
        }
    }

    private data class Inputs(
        val state: LibraryState,
        val disabled: Set<String>,
        val favorites: Set<String>,
        val skipped: Set<String>,
        val values: SettingsValues,
    )

    /**
     * Starts once bundled presets are ready (and, for a user last-key, waits up to 3 s for the user
     * index): a fast start without losing the restore of the last user preset.
     */
    fun start() {
        // Navigation while startup waits wins: startup must not issue a newer request over it.
        val before = playlist.latestRequestId
        scope.launch {
            applied.first { it?.bundledReady == true }
            val lastKey = values.lastPresetKey
            if (lastKey != null && lastKey.startsWith("user:")) {
                // Bundled readiness is published before the grant probe: wait for the probe, then
                // (if granted) the user index, all within the one 3 s budget.
                withTimeoutOrNull(USER_INDEX_WAIT_MS) {
                    applied.first { it != null && it.userProbed && (!it.hasGrant || !it.userIndexing) }
                }
            }
            if (playlist.latestRequestId != before) return@launch
            process(playlist.start(values.lastPresetKey))
        }
    }

    override fun next() = process(playlist.next())
    override fun previous() { playlist.previous()?.let(::process) }
    override fun random() = process(playlist.random())
    override fun select(key: String) { playlist.select(key)?.let(::process) }

    override suspend fun skip() {
        val key = playlist.current?.key ?: return
        val id = playlist.latestRequestId
        library.setSkipped(key, true)
        // Navigation during the write moved on: the skip stays persisted, playback is left alone.
        if (id != playlist.latestRequestId) return
        process(playlist.skipCurrent())
    }

    override suspend fun toggleFavorite() {
        val key = playlist.current?.key ?: return
        library.toggleFavorite(key)
    }

    override suspend fun setLocked(on: Boolean) {
        settings.update { it.copy(locked = on) }
    }

    /** Id of the most recently issued request. */
    fun nowPlayingRequestId(): Long = playlist.latestRequestId

    /**
     * projectM's timer or beat asks to advance. [requestId] is the request the renderer was showing:
     * events for a superseded request (a newer command is in flight or done) are dropped, as are
     * events delivered after locking or during an interstitial (its own timer advances it). An id
     * *newer* than ours came from another loader (debug harness, test, preview), since our ids never
     * exceed our latest: advance as usual, taking playback back. Ignoring it froze playback for the
     * rest of the process once anything else had loaded a preset.
     */
    override fun onSwitchRequested(hardCut: Boolean, requestId: Long) {
        if (requestId < playlist.latestRequestId) return
        if (values.locked || values.presetDurationSec == 0) return
        if (lastIssued?.kind == Kind.Interstitial) return
        hardCutPending = hardCut
        next()
    }

    override fun onPresetFailed(requestId: Long, message: String) {
        // The renderer's newest request came from another loader and failed while ours was still
        // in flight, so ours was superseded and never shown, and the screen keeps an older preset whose
        // switch events are stale. Load our latest target again, above the floor.
        if (requestId > playlist.latestRequestId && requestId == sink.currentRequestId &&
            lastLoadedId != playlist.latestRequestId
        ) {
            playlist.reissueLatest()?.let(::process)
            return
        }
        playlist.onLoadFailed(requestId)?.let(::process)
    }

    override fun onPresetLoaded(requestId: Long) {
        // A recreated renderer replays the current preset under its original id: report it once.
        if (requestId == lastLoadedId) return
        lastLoadedId = requestId
        playlist.onLoaded(requestId)
        val issued = lastIssued ?: return
        if (issued.requestId != requestId) return
        if (issued.kind == Kind.Interstitial) startInterstitialTimer(requestId)
        val ref = (issued.target as? Target.Preset)?.ref ?: return
        if (issued.kind == Kind.Normal) {
            scope.launch { settings.update { it.copy(lastPresetKey = ref.key) } }
        }
    }

    private fun process(r: PlayRequest) {
        loadJob?.cancel()
        timerJob?.cancel()
        lastIssued = r
        _nowPlaying.value = NowPlaying(
            ref = (r.target as? Target.Preset)?.ref,
            notice = r.notice,
            interstitial = r.kind == Kind.Interstitial,
        )
        val smooth = values.softCut && !hardCutPending
        hardCutPending = false
        val id = r.requestId
        fun fresh() = id == playlist.latestRequestId
        // Provider operations are keyed by the catalog they were issued against: a preset key names
        // a pack and path, not a tree, so after a folder replacement the same key must not join a
        // read still stalled on the old tree.
        val source = System.identityHashCode(libraryState.value.catalog)
        loadJob = scope.launch {
            when (val target = r.target) {
                Target.Idle -> if (fresh()) sink.loadIdle(id)
                is Target.Preset -> {
                    val user = target.ref.packId.startsWith("user:")
                    val data = try {
                        bounded("read:$source:${target.ref.key}", capped = user) { reader.read(target.ref) }
                            ?: throw IOException("preset read timed out")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: NoCapacity) {
                        // Never attempted: skip it for now rather than mark it broken.
                        if (fresh()) playlist.onLoadFailed(id, markBroken = false)?.let(::process)
                        return@launch
                    } catch (e: Exception) {
                        if (target.ref.packId.startsWith("user:")) {
                            // The folder may be gone: let the library re-check it, and
                            // pick next only once the playlist has the result, so a lost folder
                            // can't burn through the failure bound.
                            runCatching {
                                bounded("revalidate", capped = true) {
                                    onUserReadFailure()
                                    applied.first { it === libraryState.value }
                                }
                            }
                        }
                        if (fresh()) playlist.onLoadFailed(id)?.let(::process)
                        return@launch
                    }
                    if (!fresh()) return@launch
                    val paths = try {
                        bounded("textures:$source:${target.ref.packId}", capped = user) { texturePaths(target.ref.packId) }
                    } catch (e: NoCapacity) {
                        if (fresh()) playlist.onLoadFailed(id, markBroken = false)?.let(::process)
                        return@launch
                    }
                    if (paths == null) {
                        if (fresh()) playlist.onLoadFailed(id)?.let(::process)
                        return@launch
                    }
                    if (!fresh()) return@launch
                    sink.load(data, id, smooth, paths)
                }
            }
        }
    }

    /** Provider work (reads, texture paths, revalidation) outlives the load that asked for it. */
    private val detached = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /** In-flight provider operations by key; a duplicate request joins the one already running. */
    private val inFlight = HashMap<String, Deferred<*>>()
    private val cappedKeys = HashSet<String>()

    /** No capacity for another user-folder operation; the preset was not attempted. */
    private class NoCapacity : RuntimeException()

    /**
     * Runs [block] once per [key] on [detached] and waits at most [LOAD_TIMEOUT_MS] for its result,
     * else null. A provider that never answers (a stalled cloud folder) would otherwise stall
     * playback: timer events for the preset still on screen are dropped as stale while the new one
     * never arrives. The wait is abandoned, never the work: cancellation can't interrupt a blocking
     * content-provider read, and cancelling a folder revalidation mid-way would leave the library
     * half updated. Work can't pile up either: the same key (operation, source catalog, target)
     * joins the operation already running, and at most [MAX_DETACHED] distinct user-folder operations
     * run at once, beyond which [NoCapacity] is thrown and the preset is skipped, not broken. A late
     * result is dropped, since only the current load job talks to the sink. Main thread only.
     */
    private suspend fun <T> bounded(key: String, capped: Boolean, block: suspend () -> T): T? {
        @Suppress("UNCHECKED_CAST")
        val job = inFlight[key] as Deferred<T>? ?: run {
            // Only user-folder work can stall, so only it is capped: bundled assets must keep
            // playing however many folder reads are stuck.
            if (capped && inFlight.keys.count { it in cappedKeys } >= MAX_DETACHED) throw NoCapacity()
            if (capped) cappedKeys += key
            detached.async { block() }.also { d ->
                inFlight[key] = d
                d.invokeOnCompletion { scope.launch { if (inFlight[key] === d) { inFlight.remove(key); cappedKeys.remove(key) } } }
            }
        }
        return withTimeoutOrNull(LOAD_TIMEOUT_MS) { job.await() }
    }

    /**
     * An interstitial's duration is its time on screen, so its timer starts when the renderer
     * reports it loaded, not when the load was issued: reading, texture setup and shader
     * compilation can take seconds on a slow phone and would otherwise eat the interstitial.
     */
    private fun startInterstitialTimer(id: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(values.interstitialSec * 1000L)
            if (id == playlist.latestRequestId) playlist.onInterstitialElapsed(id)?.let(::process)
        }
    }

    companion object {
        private const val USER_INDEX_WAIT_MS = 3_000L
        /** Reading a preset's text or texture paths longer than this counts as a failed load. */
        const val LOAD_TIMEOUT_MS = 10_000L
        /** Distinct user-folder operations allowed in flight at once; beyond that a preset is skipped. */
        const val MAX_DETACHED = 4
    }
}
