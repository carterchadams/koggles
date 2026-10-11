package dev.koggles

import dev.koggles.render.GlExecutor
import dev.koggles.render.NativeRendererPort
import dev.koggles.render.RenderCommands

/** Process-wide singletons. Later tasks add services here as `val`s. */
object AppGraph {
    lateinit var app: KogglesApp
        private set

    /** Forwards to the GL thread of the current [dev.koggles.render.VisualizerView], if any. */
    val glExecutor = ActiveGlExecutor()

    lateinit var renderCommands: RenderCommands
        private set

    fun init(app: KogglesApp) {
        this.app = app
        renderCommands = RenderCommands(glExecutor, NativeRendererPort(app.resources.displayMetrics.xdpi))
    }
}

class ActiveGlExecutor : GlExecutor {
    @Volatile
    var target: GlExecutor? = null

    override fun run(block: () -> Unit) {
        target?.run(block)
    }
}
