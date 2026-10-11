package dev.koggles.playback

import dev.koggles.NativeListener
import dev.koggles.presets.LibraryState
import dev.koggles.presets.PresetLibrary
import dev.koggles.presets.PresetRef
import dev.koggles.settings.Settings
import dev.koggles.settings.SettingsValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where loads go; [dev.koggles.render.RenderCommands] in production. */
interface RenderSink {
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
 * F2: a request superseded while its preset text or texture paths were being fetched never reaches
 * the sink, and its outcome never reaches the playlist.
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
) : NativeListener {

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private var values = SettingsValues()
    private var lastIssued: PlayRequest? = null
    private var loadJob: Job? = null
    private var timerJob: Job? = null
    private var hardCutPending = false

    /** The library state most recently applied to the playlist (null until the first update). */
    private val applied = MutableStateFlow<LibraryState?>(null)

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

    /** D8: once bundled presets are ready (and, for a user last-key, up to 3 s for the user index). */
    fun start() {
        scope.launch {
            applied.first { it?.bundledReady == true }
            val lastKey = values.lastPresetKey
            if (lastKey != null && lastKey.startsWith("user:") && applied.value?.hasGrant == true) {
                withTimeoutOrNull(USER_INDEX_WAIT_MS) { applied.first { it != null && !it.userIndexing } }
            }
            process(playlist.start(values.lastPresetKey))
        }
    }

    fun next() = process(playlist.next())
    fun previous() { playlist.previous()?.let(::process) }
    fun random() = process(playlist.random())
    fun select(key: String) { playlist.select(key)?.let(::process) }

    suspend fun skip() {
        val key = playlist.current?.key ?: return
        library.setSkipped(key, true)
        process(playlist.skipCurrent())
    }

    suspend fun toggleFavorite() {
        val key = playlist.current?.key ?: return
        library.toggleFavorite(key)
    }

    suspend fun setLocked(on: Boolean) {
        settings.update { it.copy(locked = on) }
    }

    /** Id of the most recently issued request. */
    fun nowPlayingRequestId(): Long = playlist.latestRequestId

    override fun onSwitchRequested(hardCut: Boolean) {
        hardCutPending = hardCut
        next()
    }

    override fun onPresetFailed(requestId: Long, message: String) {
        playlist.onLoadFailed(requestId)?.let(::process)
    }

    override fun onPresetLoaded(requestId: Long) {
        playlist.onLoaded(requestId)
        val issued = lastIssued ?: return
        val ref = (issued.target as? Target.Preset)?.ref ?: return
        if (issued.requestId == requestId && issued.kind == Kind.Normal) {
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
        loadJob = scope.launch {
            when (val target = r.target) {
                Target.Idle -> if (fresh()) sink.loadIdle(id)
                is Target.Preset -> {
                    val data = try {
                        reader.read(target.ref)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (fresh()) playlist.onLoadFailed(id)?.let(::process)
                        return@launch
                    }
                    if (!fresh()) return@launch
                    val paths = texturePaths(target.ref.packId)
                    if (!fresh()) return@launch
                    sink.load(data, id, smooth, paths)
                }
            }
        }
        if (r.kind == Kind.Interstitial) {
            timerJob = scope.launch {
                delay(values.interstitialSec * 1000L)
                if (fresh()) playlist.onInterstitialElapsed(id)?.let(::process)
            }
        }
    }

    private companion object {
        const val USER_INDEX_WAIT_MS = 3_000L
    }
}
