package dev.koggles

/** JNI entry points. `renderer*` functions must be called on the GL thread. */
object NativeBridge {
    init {
        System.loadLibrary("koggles")
    }

    external fun nativeInit()
    /**
     * Once per process, with a display context derived from the Application (never an Activity):
     * the SDK keeps a JNI global reference to the
     * context it is given, so passing an Activity would pin it. The QR scanner is launched from
     * Kotlin with an Activity instead of through the SDK.
     */
    external fun cardboardInit(appContext: android.content.Context)

    /** Diagnostic: creates and destroys a Cardboard lens distortion for the default viewer. */
    external fun cardboardLensProbe(width: Int, height: Int): Boolean

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

    /** Sets projectM's texture search paths; textures are reset only when the list changed. */
    external fun rendererSetTextureSearchPaths(h: Long, paths: Array<String>)
    external fun rendererLastGlError(h: Long): Int
    external fun rendererSetTransition(
        h: Long, softCut: Boolean, blendSec: Double, presetDurationSec: Int, hardCuts: Boolean, locked: Boolean,
    )
    external fun rendererSetGoggles(h: Long, on: Boolean)
    external fun rendererSetLens(h: Long, offsetMm: Float, distortion: Boolean, xdpi: Float)

    /** Diagnostic, any thread: whether the renderer is in goggles mode. */
    external fun rendererGogglesActive(h: Long): Boolean

    /** GL thread: {p50 ms, p95 ms, frames} of frame-to-frame time since the last call. */
    external fun rendererFrameStats(h: Long): DoubleArray

    /** Cardboard's counter of saved viewer-profile changes (increments after a QR scan). */
    external fun viewerParamsChangedCount(): Int

    /** Receives renderer events on the main thread. */
    @Volatile
    var listener: NativeListener? = null

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Upcalls from the GL thread (upcalls.cpp); re-posted to the main thread.
    // Each logs once per preset change (tag KogglesUp): the release smoke test reads these to prove
    // the native → Kotlin upcalls survive R8.
    @JvmStatic fun onSwitchRequested(hardCut: Boolean, requestId: Long) {
        android.util.Log.i(UP_TAG, "switch $requestId")
        main.post { listener?.onSwitchRequested(hardCut, requestId) }
    }
    @JvmStatic fun onPresetFailed(requestId: Long, message: String) {
        android.util.Log.i(UP_TAG, "failed $requestId")
        main.post { listener?.onPresetFailed(requestId, message) }
    }
    @JvmStatic fun onPresetLoaded(requestId: Long) {
        android.util.Log.i(UP_TAG, "loaded $requestId")
        main.post { listener?.onPresetLoaded(requestId) }
    }

    private const val UP_TAG = "KogglesUp"
}
