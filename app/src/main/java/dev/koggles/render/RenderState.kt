package dev.koggles.render

data class LensState(val offsetMm: Float = 0f, val distortion: Boolean = true)

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
    val transition: TransitionState = TransitionState(),
    val texturePaths: List<String> = emptyList(),
) {
    val effectiveGoggles: Boolean get() = goggles && !menuPreview
}
