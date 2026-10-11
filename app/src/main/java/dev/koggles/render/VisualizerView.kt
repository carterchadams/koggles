package dev.koggles.render

import android.content.Context
import android.opengl.GLSurfaceView
import dev.koggles.DebugHooks
import dev.koggles.NativeBridge
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VisualizerView(context: Context) : GLSurfaceView(context) {

    /** Called on the GL thread after the renderer is created and sized. */
    var onRendererReady: ((Long) -> Unit)? = null

    private var handle = 0L

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                // A new EGL context: any previous native renderer belongs to a dead context.
                if (handle != 0L) {
                    NativeBridge.rendererDestroy(handle)
                    handle = 0L
                }
                handle = NativeBridge.rendererCreate()
                sized = false // readiness fires again for every new context
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                val first = handle != 0L && !sized
                NativeBridge.rendererSurfaceChanged(handle, width, height)
                if (first) {
                    sized = true
                    onRendererReady?.invoke(handle)
                    DebugHooks.onRendererReady(handle)
                }
            }

            override fun onDrawFrame(gl: GL10?) {
                if (handle != 0L) NativeBridge.rendererDrawFrame(handle)
            }

            private var sized = false
        })
    }
}
