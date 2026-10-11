package dev.koggles.render

import android.content.Context
import android.opengl.GLSurfaceView
import dev.koggles.AppGraph
import dev.koggles.DebugHooks
import dev.koggles.NativeBridge
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Hosts the GL thread. The native renderer lives as long as its EGL context: pauses that
 * preserve the context keep it, and a new context replaces it via [RenderCommands.attach].
 */
class VisualizerView(
    context: Context,
    private val commands: RenderCommands = AppGraph.renderCommands,
) : GLSurfaceView(context), GlExecutor {

    /** Called on the GL thread after a renderer is created and sized, once per EGL context. */
    var onRendererReady: ((Long) -> Unit)? = null

    private var sized = false

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                // A new EGL context. Destroy the previous renderer before creating the new
                // one: the fresh context owns no GL names yet, so its deletes are no-ops.
                val old = commands.attachedHandle
                if (old != 0L) NativeBridge.rendererDestroy(old)
                val h = NativeBridge.rendererCreate()
                commands.attach(h)
                sized = false
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                val h = commands.attachedHandle
                if (h == 0L) return
                NativeBridge.rendererSurfaceChanged(h, width, height)
                if (!sized) {
                    sized = true
                    onRendererReady?.invoke(h)
                    DebugHooks.onRendererReady(h)
                }
            }

            override fun onDrawFrame(gl: GL10?) {
                val h = commands.attachedHandle
                if (h != 0L) NativeBridge.rendererDrawFrame(h)
            }
        })
    }

    override fun run(block: () -> Unit) = queueEvent(block)
}
