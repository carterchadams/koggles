package dev.koggles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.koggles.ui.menu.ANALYTICS_EXPLANATION
import dev.koggles.ui.menu.ANALYTICS_SWITCH

const val EXPLAINER_TEXT =
    "Android calls this 'record audio'. Koggles reads what your phone is playing and never uses the microphone."

/** The photosensitivity warning: the first thing a new user reads, above the permission text. */
const val WARNING_TEXT =
    "Warning: the visualizations include intense moving and flashing lights. They are not recommended for people " +
        "with epilepsy, who suffer migraines, experience nausea, or who are otherwise sensitive to bright visual " +
        "stimulation."

/**
 * Shown once, before the first RECORD_AUDIO request. [analytics] offers the anonymous usage statistics switch
 * (the consent gate's state, off on a fresh install; null = unavailable in this build, nothing shown), so a new
 * user decides once with everything in front of them; Continue proceeds either way.
 */
@Composable
fun Explainer(analytics: Boolean? = null, onAnalyticsChange: (Boolean) -> Unit = {}, onContinue: () -> Unit) {
    MessageScreen(EXPLAINER_TEXT, "Continue", onContinue, notice = WARNING_TEXT) {
        if (analytics != null) AnalyticsChoice(analytics, onAnalyticsChange)
    }
}

@Composable
private fun AnalyticsChoice(on: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(ANALYTICS_SWITCH, Modifier.weight(1f), color = Color.White)
        Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.testTag("switch:$ANALYTICS_SWITCH"))
    }
    Text(ANALYTICS_EXPLANATION, color = Color.White, textAlign = TextAlign.Center)
}

@Composable
internal fun MessageScreen(
    message: String,
    action: String,
    onAction: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    notice: String? = null,
    /** Drawn between the message and the button. */
    extra: @Composable () -> Unit = {},
) {
    // Centred while it fits; scrolls (landscape, large fonts) so the button is always reachable.
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notice != null) {
                Text(notice, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
            }
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            extra()
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
            if (secondary != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onSecondary) { Text(secondary) }
            }
        }
    }
}
