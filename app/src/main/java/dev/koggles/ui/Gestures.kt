package dev.koggles.ui

import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext

/**
 * Spec §4 gestures over the visualization: a confirmed single tap (after the double-tap timeout)
 * and a double tap. Consumes every touch it receives.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.visualizerGestures(
    singleTapEnabled: () -> Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val enabled = rememberUpdatedState(singleTapEnabled)
    val single = rememberUpdatedState(onSingleTap)
    val double = rememberUpdatedState(onDoubleTap)
    val detector = remember(context) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (enabled.value()) single.value()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                double.value()
                return true
            }
        })
    }
    pointerInteropFilter { detector.onTouchEvent(it); true }
}

/**
 * Makes an overlay opaque to touches by hit-testing alone: this node is hit, so siblings beneath it
 * (the visualization's gestures) never receive the touch. It consumes nothing, so children's
 * drags and taps work normally. Consuming here instead cancels slow drags before they pass touch
 * slop (docs/diagnosis/2026-10-11-field-report-1-diagnosis.md, P1).
 */
fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {}
