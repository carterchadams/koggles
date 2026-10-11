package dev.koggles

import dev.koggles.render.NativeRendererPort
import dev.koggles.render.RenderCommands

/** Process-wide singletons. Later tasks add services here as `val`s. */
object AppGraph {
    lateinit var app: KogglesApp
        private set

    lateinit var renderCommands: RenderCommands
        private set

    fun init(app: KogglesApp) {
        this.app = app
        renderCommands = RenderCommands(NativeRendererPort(app.resources.displayMetrics.xdpi))
    }
}
