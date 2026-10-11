package dev.koggles

import androidx.datastore.preferences.preferencesDataStoreFile
import dev.koggles.settings.KogglesStore
import dev.koggles.audio.AudioSession
import dev.koggles.audio.AudioSnapshot
import dev.koggles.audio.NativePcmSink
import dev.koggles.audio.PcmGate
import dev.koggles.audio.PlaybackCaptureSource
import dev.koggles.audio.Source
import dev.koggles.audio.UnavailableSource
import dev.koggles.audio.VisualizerSource
import dev.koggles.presets.PresetLibrary
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

    /** Every PCM producer pushes through this gate, tagged with its source generation. */
    val pcmGate = PcmGate(NativePcmSink)

    private val appScope = MainScope()

    fun init(app: KogglesApp) {
        this.app = app
        val store = KogglesStore.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            app.preferencesDataStoreFile("koggles")
        }
        settings = Settings(store)
        library = PresetLibrary(store)
        // One small blocking read at process start so the audio machine begins with the persisted
        // source and explainer flag; DataStore reads on its own IO scope, so this cannot deadlock main.
        val persisted = runBlocking { settings.values.first() }
        renderCommands = RenderCommands(NativeRendererPort(app.resources.displayMetrics.xdpi))
        audio = AudioSession(
            factory = { source, consent, generation ->
                when (source) {
                    Source.Standard -> VisualizerSource(onWaveform = { pcmGate.pushU8(generation, it, it.size) })
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
    }
}
