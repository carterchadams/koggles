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
    onSwitchToStandard: () -> Unit,
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
            // D2: another app holds the output-mix Visualizer; retries run automatically.
            FailReason.VisualizerBusy -> MessageScreen(
                "Another app is using the audio visualizer. Koggles will start as soon as it's free.",
                "Switch to High quality",
                onSwitchToHighQuality,
            )
            // High quality failures cover the menu: offer the way back to Standard here.
            FailReason.CaptureStartFailed ->
                MessageScreen("Audio capture couldn't start.", "Try again", onRetry, STANDARD, onSwitchToStandard)
            FailReason.ConsentDeclined ->
                MessageScreen("Audio capture needs your permission.", "Try again", onRetry, STANDARD, onSwitchToStandard)
            FailReason.CaptureLost ->
                MessageScreen("Audio capture stopped.", "Try again", onRetry, STANDARD, onSwitchToStandard)
        }
        else -> Unit
    }
}

private const val STANDARD = "Use Standard audio"

fun AudioState.isAudioError(): Boolean = this is AudioState.PermissionDenied || this is AudioState.Failed
