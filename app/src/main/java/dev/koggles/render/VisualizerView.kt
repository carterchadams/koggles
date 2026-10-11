package dev.koggles.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import dev.koggles.AppGraph
import dev.koggles.DebugHooks
import dev.koggles.NativeBridge
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Hosts the GL thread. The native renderer lives as long as its EGL context: pauses that
 * preserve the context keep it, and a new context replaces it via [RenderCommands.attach].
 * Renderer commands are applied in [onDrawFrame] via [RenderCommands.drain], never from
 * `queueEvent`, because queued events can run while no EGL context is current.
 */
class VisualizerView(
    context: Context,
    private val commands: RenderCommands = AppGraph.renderCommands,
) : GLSurfaceView(context) {

    /** Called on the GL thread after a renderer is created and sized, once per EGL context. */
    var onRendererReady: ((Long) -> Unit)? = null

    /** GL thread: called after each real frame with that frame's GL error. Test observation hook. */
    @Volatile
    var onFrameDrawn: ((glError: Int) -> Unit)? = null

    /** GL error of the first frame drawn in the current EGL context (-1 until drawn). */
    @Volatile
    var firstFrameGlError: Int = -1
        private set

    private var sized = false

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                // A new EGL context: the previous renderer's GL objects died with its context.
                // Destroying it frees its CPU state; its deletes of stale names (programs in
                // particular) raise GL errors in this fresh context, which are cleared here so
                // they cannot surface on the first frame.
                val old = commands.attachedHandle
                if (old != 0L) {
                    NativeBridge.rendererDestroy(old)
                    val cleared = NativeBridge.glDrainErrors()
                    if (cleared > 0) Log.d("Koggles", "cleared $cleared GL errors from stale renderer teardown")
                }
                val h = NativeBridge.rendererCreate()
                commands.attach(h)
                sized = false
                firstFrameGlError = -1
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                val h = commands.handleForCurrentThread()
                if (h == 0L) return
                NativeBridge.rendererSurfaceChanged(h, width, height)
                if (!sized) {
                    sized = true
                    onRendererReady?.invoke(h)
                    DebugHooks.onRendererReady(h)
                }
            }

            override fun onDrawFrame(gl: GL10?) {
                // Only the GL thread that attached the renderer may touch it, and only here,
                // where its context and surface are current.
                val h = commands.handleForCurrentThread()
                if (h == 0L) return
                commands.drain()
                NativeBridge.rendererDrawFrame(h)
                val error = NativeBridge.rendererLastGlError(h)
                if (firstFrameGlError == -1) firstFrameGlError = error
                onFrameDrawn?.invoke(error)
            }
        })
    }

}
