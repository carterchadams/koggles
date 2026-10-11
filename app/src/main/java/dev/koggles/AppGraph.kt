package dev.koggles

import dev.koggles.audio.AudioSession
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

    private val appScope = MainScope()

    fun init(app: KogglesApp) {
        this.app = app
        renderCommands = RenderCommands(NativeRendererPort(app.resources.displayMetrics.xdpi))
        audio = AudioSession(
            factory = { source, consent, generation ->
                when (source) {
                    Source.Standard -> VisualizerSource()
                    Source.HighQuality ->
                        if (consent != null) PlaybackCaptureSource(app, consent, generation) else UnavailableSource()
                }
            },
            flush = { NativeBridge.flushAudio() },
            scope = appScope,
            explainerStore = {}, // in-memory until Task 15 persists it
        )
    }
}
