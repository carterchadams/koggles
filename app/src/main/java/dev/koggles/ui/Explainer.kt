package dev.koggles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val EXPLAINER_TEXT =
    "Android calls this 'record audio'. Koggles reads what your phone is playing and never uses the microphone."

/** Shown before the first RECORD_AUDIO request. */
@Composable
fun Explainer(onContinue: () -> Unit) {
    MessageScreen(EXPLAINER_TEXT, "Continue", onContinue)
}

@Composable
internal fun MessageScreen(
    message: String,
    action: String,
    onAction: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction) { Text(action) }
        if (secondary != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSecondary) { Text(secondary) }
        }
    }
}
