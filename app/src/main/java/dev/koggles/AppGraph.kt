package dev.koggles

import dev.koggles.audio.AudioSession
import dev.koggles.audio.NativePcmSink
import dev.koggles.audio.PcmGate
import dev.koggles.audio.PlaybackCaptureSource
import dev.koggles.audio.Source
import dev.koggles.audio.UnavailableSource
import dev.koggles.audio.VisualizerSource
import dev.koggles.render.NativeRendererPort
import dev.koggles.render.RenderCommands
import kotlinx.coroutines.MainScope

/** Process-wide singletons. Later tasks add services here as `val`s. */
object AppGraph {
    lateinit var app: KogglesApp
        private set

    lateinit var renderCommands: RenderCommands
        private set

    lateinit var audio: AudioSession
        private set

    /** Every PCM producer pushes through this gate, tagged with its source generation. */
    val pcmGate = PcmGate(NativePcmSink)

    private val appScope = MainScope()

    fun init(app: KogglesApp) {
        this.app = app
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
            explainerStore = {}, // in-memory until Task 15 persists it
        )
    }
}
