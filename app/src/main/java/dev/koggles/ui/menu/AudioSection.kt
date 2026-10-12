package dev.koggles.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioSnapshot
import dev.koggles.audio.AudioState
import dev.koggles.audio.Source

/** Fixed user-visible copy for the High quality caveat. */
const val HQ_CAVEAT = "Higher fidelity; some apps block it and it asks again after locking"

/** Fixed user-visible copy for prolonged silence. */
const val SILENCE_HINT = "No audio detected — some apps block capture"

internal fun LazyListScope.audioSection(snapshot: AudioSnapshot, silence: Boolean, actions: MenuActions) {
    item(key = "audio-header") { SectionHeader("Audio") }
    item(key = "audio-body") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = snapshot.source == Source.Standard,
                onClick = { actions.dispatchAudio(AudioEvent.SelectSource(Source.Standard)) },
                label = { Text("Standard") },
            )
            FilterChip(
                selected = snapshot.source == Source.HighQuality,
                onClick = { actions.dispatchAudio(AudioEvent.SelectSource(Source.HighQuality)) },
                label = { Text("High quality") },
            )
        }
        Text("High quality: $HQ_CAVEAT", style = MaterialTheme.typography.bodySmall)
        Text("Status: ${statusText(snapshot.state)}")
        if (snapshot.source == Source.HighQuality) {
            when (snapshot.state) {
                AudioState.Active, AudioState.Starting ->
                    OutlinedButton(onClick = { actions.dispatchAudio(AudioEvent.UserStop) }) { Text("Stop") }
                AudioState.StoppedByUser ->
                    OutlinedButton(onClick = { actions.dispatchAudio(AudioEvent.UserResume) }) { Text("Resume") }
                else -> Unit
            }
        }
        if (silence) Text(SILENCE_HINT, color = MaterialTheme.colorScheme.tertiary)
    }
}

private fun statusText(s: AudioState): String = when (s) {
    AudioState.Active -> "Listening"
    AudioState.Starting -> "Starting…"
    AudioState.Idle -> "Idle"
    AudioState.ShowingExplainer, AudioState.AwaitingPermission -> "Waiting for permission"
    is AudioState.PermissionDenied -> "Permission denied"
    AudioState.NeedsConsent -> "Will ask to share audio when you return"
    is AudioState.AwaitingConsent -> "Waiting for consent"
    AudioState.StoppedByUser -> "Stopped"
    is AudioState.Failed -> "Unavailable (${s.reason})"
}
