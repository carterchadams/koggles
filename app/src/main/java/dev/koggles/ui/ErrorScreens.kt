package dev.koggles.ui

import androidx.compose.runtime.Composable
import dev.koggles.audio.AudioState
import dev.koggles.audio.FailReason

/** Full-screen audio error, or null-render when [state] is not an error. */
@Composable
fun AudioErrorScreen(
    state: AudioState,
    onRetry: () -> Unit,
    onSwitchToHighQuality: () -> Unit,
) {
    when (state) {
        is AudioState.PermissionDenied -> MessageScreen(
            "Koggles needs audio access to react to what your phone is playing.",
            if (state.permanent) "Open settings" else "Grant",
            onRetry,
        )
        is AudioState.Failed -> when (state.reason) {
            FailReason.VisualizerUnavailable -> MessageScreen(
                "This phone doesn't share its audio with visualizers.",
                "Switch to High quality",
                onSwitchToHighQuality,
            )
            FailReason.CaptureStartFailed -> MessageScreen("Audio capture couldn't start.", "Try again", onRetry)
            FailReason.ConsentDeclined -> MessageScreen("Audio capture needs your permission.", "Try again", onRetry)
        }
        else -> Unit
    }
}

fun AudioState.isAudioError(): Boolean = this is AudioState.PermissionDenied || this is AudioState.Failed
