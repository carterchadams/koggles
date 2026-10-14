package dev.koggles.render

/** [wideView]: 0 normal, 1 the profile's field of view opened to the screen edges, 2 also a window per eye. */
data class LensState(val offsetMm: Float = 0f, val distortion: Boolean = true, val wideView: Int = 0)

data class TransitionState(
    val softCut: Boolean = true,
    val blendSec: Double = 3.0,
    val presetDurationSec: Int = 30,
    val hardCutsOnBeat: Boolean = false,
    val locked: Boolean = false,
)

/** A preset load request; `data == null` means projectM's idle preset. */
data class PresetLoad(val requestId: Long, val data: String?)

data class RenderState(
    val preset: PresetLoad? = null,
    val goggles: Boolean = false,
    val menuPreview: Boolean = false,
    val lens: LensState = LensState(),
    /** Spacing calibration (bullseyes in place of the visualizer); never persisted, goggles mode only. */
    val calibration: Boolean = false,
    val transition: TransitionState = TransitionState(),
    val texturePaths: List<String> = emptyList(),
) {
    val effectiveGoggles: Boolean get() = goggles && !menuPreview
}
