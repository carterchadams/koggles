package dev.koggles

/** JNI entry points. `renderer*` functions must be called on the GL thread. */
object NativeBridge {
    init {
        System.loadLibrary("koggles")
    }

    external fun nativeInit()

    /** Main thread, from `MainActivity.onCreate`: `Cardboard_initializeAndroid(vm, activity)` (D5). */
    external fun cardboardInit(activity: android.app.Activity)

    /**
     * Any thread. [buf] must be a direct buffer of stereo interleaved floats; [frames] frames are
     * read from its start (position ignored). Counts beyond its capacity are rejected.
     */
    external fun pushPcmFloat(buf: java.nio.FloatBuffer, frames: Int)

    /** Any thread. 8-bit unsigned mono samples (Visualizer waveform). */
    external fun pushPcmU8(buf: ByteArray, frames: Int)
    external fun flushAudio()
    external fun recentRms(): Float

    /** GL thread: clears pending GL error flags; returns how many were cleared. */
    external fun glDrainErrors(): Int

    /** Debug aid: number of native renderers destroyed in this process. */
    external fun rendererDestroyCount(): Int

    external fun rendererCreate(): Long
    external fun rendererDestroy(h: Long)
    external fun rendererSurfaceChanged(h: Long, width: Int, height: Int)
    external fun rendererDrawFrame(h: Long)
    external fun rendererLoadPresetData(h: Long, data: String, requestId: Long, smooth: Boolean)
    external fun rendererLoadIdle(h: Long, requestId: Long)
    external fun rendererLastGlError(h: Long): Int
}
