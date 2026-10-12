package dev.koggles

import androidx.datastore.preferences.preferencesDataStoreFile
import dev.koggles.settings.KogglesStore
import dev.koggles.audio.AudioSession
import dev.koggles.presets.AndroidAssetSource
import dev.koggles.presets.AssetDocTree
import dev.koggles.presets.FolderGrant
import dev.koggles.presets.LibraryRepository
import dev.koggles.presets.PresetIndexer
import dev.koggles.presets.SafDocTree
import dev.koggles.presets.TextureInstaller
import dev.koggles.presets.TextureMirror
import dev.koggles.audio.AudioSnapshot
import dev.koggles.audio.NativePcmSink
import dev.koggles.audio.PcmGate
import dev.koggles.audio.PlaybackCaptureSource
import dev.koggles.audio.Source
import dev.koggles.audio.UnavailableSource
import dev.koggles.audio.VisualizerSource
import dev.koggles.presets.PresetLibrary
import dev.koggles.playback.PlaybackController
import dev.koggles.playback.PlaybackControls
import dev.koggles.audio.AudioState
import dev.koggles.ui.MenuState
import dev.koggles.ui.SilenceDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import dev.koggles.playback.Playlist
import dev.koggles.render.LensState
import dev.koggles.render.TransitionState
import dev.koggles.render.NativeRendererPort
import dev.koggles.render.RenderCommands
import dev.koggles.settings.Settings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** The one preferences file; [Settings] and [PresetLibrary] use disjoint key prefixes in it. */

/** Process-wide singletons. Later tasks add services here as `val`s. */
object AppGraph {
    lateinit var app: KogglesApp
        private set

    lateinit var renderCommands: RenderCommands
        private set

    lateinit var audio: AudioSession
        private set

    lateinit var settings: Settings
        private set

    lateinit var library: PresetLibrary
        private set

    lateinit var folderGrant: FolderGrant
        private set

    lateinit var libraryRepo: LibraryRepository
        private set

    lateinit var playback: PlaybackController
        private set

    lateinit var menu: MenuState
        private set

    lateinit var folderPicker: dev.koggles.presets.FolderPicker
        private set

    @Volatile private var playbackOverride: PlaybackControls? = null

    /** What the UI drives: [playback], unless a test has overridden it. */
    val playbackControls: PlaybackControls get() = playbackOverride ?: playback

    @Volatile private var audioSourceOverride: dev.koggles.audio.SourceFactory? = null

    /** Instrumented tests only: audio sources come from [factory] (null restores the real ones). */
    fun overrideAudioForTest(factory: dev.koggles.audio.SourceFactory?) {
        audioSourceOverride = factory
    }

    /** Instrumented tests only: replaces what the UI drives (null restores [playback]). */
    fun overrideForTest(playback: PlaybackControls?) {
        playbackOverride = playback
    }

    private val _silenceHint = MutableStateFlow(false)

    /** Spec §7: true after 10 s of silence while audio is Active; false otherwise. */
    val silenceHint: StateFlow<Boolean> = _silenceHint.asStateFlow()

    /** Every PCM producer pushes through this gate, tagged with its source generation. */
    val pcmGate = PcmGate(NativePcmSink)

    /** Process-lifetime main scope: work that must outlive any activity or composition. */
    val appScope = MainScope()

    fun init(app: KogglesApp) {
        this.app = app
        val store = KogglesStore.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            app.preferencesDataStoreFile("koggles")
        }
        settings = Settings(store)
        library = PresetLibrary(store)
        folderGrant = FolderGrant(app)
        libraryRepo = LibraryRepository(
            scope = appScope,
            bundledTree = { AssetDocTree(AndroidAssetSource(app.assets), "presets") },
            installTextures = {
                val versionKey = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                TextureInstaller(AndroidAssetSource(app.assets), app.filesDir, versionKey).installIfNeeded()
            },
            userTree = { folderGrant.currentTree() },
            mirror = TextureMirror(
                app.cacheDir,
                copy = { node, _ -> throw java.io.IOException("no tree for ${node.id}") },
                log = { android.util.Log.w("KogglesLib", it) },
                // Read each node from the tree that indexed it, never from a newer grant.
                copyFrom = { tree, node, dest ->
                    val saf = tree as? SafDocTree ?: throw java.io.IOException("unsupported tree for ${node.id}")
                    saf.openStream(node).use { src -> dest.outputStream().use { src.copyTo(it) } }
                },
            ),
            indexer = PresetIndexer(kotlinx.coroutines.Dispatchers.IO),
            log = { android.util.Log.i("KogglesLib", it) },
        )
        // One small blocking read at process start so the audio machine begins with the persisted
        // source and explainer flag; DataStore reads on its own IO scope, so this cannot deadlock main.
        val persisted = runBlocking { settings.values.first() }
        renderCommands = RenderCommands(NativeRendererPort(app.resources.displayMetrics.xdpi))
        audio = AudioSession(
            factory = { source, consent, generation ->
                audioSourceOverride?.create(source, consent, generation) ?: when (source) {
                    Source.Standard -> VisualizerSource(
                        onWaveform = { pcmGate.pushU8(generation, it, it.size) },
                        log = { android.util.Log.w("KogglesAudio", it) },
                    )
                    Source.HighQuality ->
                        if (consent != null) PlaybackCaptureSource(app, consent, generation) else UnavailableSource()
                }
            },
            gate = pcmGate,
            scope = appScope,
            explainerStore = { settings.update { it.copy(explainerSeen = true) } },
            initial = AudioSnapshot(source = persisted.source, explainerSeen = persisted.explainerSeen),
        )
        // SelectSource only changes the snapshot; persist each distinct source after the initial one.
        appScope.launch {
            audio.snapshot.map { it.source }.distinctUntilChanged().drop(1).collect { source ->
                settings.update { it.copy(source = source) }
            }
        }
        playback = PlaybackController(
            scope = appScope,
            playlist = Playlist(Random.Default),
            sink = renderCommands,
            reader = { ref ->
                withContext(Dispatchers.IO) {
                    val pack = libraryRepo.state.value.catalog.pack(ref.packId)
                        ?: throw java.io.IOException("pack ${ref.packId} is gone")
                    pack.tree.readText(ref.node)
                }
            },
            texturePaths = libraryRepo::texturePathsFor,
            settings = settings,
            library = library,
            libraryState = libraryRepo.state,
            onUserReadFailure = { libraryRepo.revalidate() },
        )
        NativeBridge.listener = playback
        // Renderer-side settings: replayed onto each new renderer by RenderCommands.
        appScope.launch {
            settings.values.collect { v ->
                renderCommands.setTransition(
                    TransitionState(v.softCut, v.blendSec, v.presetDurationSec, v.hardCutsOnBeat, v.locked),
                )
                renderCommands.setGoggles(v.goggles)
                renderCommands.setLens(LensState(v.lensOffsetMm, v.distortion))
            }
        }
        folderPicker = dev.koggles.presets.FolderPicker(
            scope = appScope,
            io = Dispatchers.IO,
            acquire = { uri -> folderGrant.onTreePicked(uri) },
            onAcquired = { libraryRepo.refresh() },
            onFailed = { libraryRepo.reportError(it) },
        )
        menu = MenuState(setPreview = renderCommands::setMenuPreview, dispatch = audio::dispatch)
        appScope.launch {
            audio.snapshot.map { it.state == AudioState.Active }.distinctUntilChanged().collectLatest { active ->
                _silenceHint.value = false
                if (!active) return@collectLatest
                val detector = SilenceDetector()
                while (true) {
                    delay(SILENCE_SAMPLE_MS)
                    _silenceHint.value = detector.sample(NativeBridge.recentRms(), android.os.SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private const val SILENCE_SAMPLE_MS = 250L
}
