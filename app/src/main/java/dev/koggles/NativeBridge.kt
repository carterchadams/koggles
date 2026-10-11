package dev.koggles

/** JNI entry points. `renderer*` functions must be called on the GL thread. */
object NativeBridge {
    init {
        System.loadLibrary("koggles")
    }

    external fun rendererCreate(): Long
    external fun rendererDestroy(h: Long)
    external fun rendererSurfaceChanged(h: Long, width: Int, height: Int)
    external fun rendererDrawFrame(h: Long)
    external fun rendererLoadPresetData(h: Long, data: String, requestId: Long, smooth: Boolean)
    external fun rendererLoadIdle(h: Long, requestId: Long)
    external fun rendererLastGlError(h: Long): Int
}
