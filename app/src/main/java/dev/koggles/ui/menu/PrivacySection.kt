package dev.koggles.ui.menu

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/** The switch label (copy pending the author's approval, 2026-10-14). The explainer shows the same switch. */
const val ANALYTICS_SWITCH = "Share anonymous usage statistics"

/** What the switch sends, in one line; the README's Privacy section has the full list. */
const val ANALYTICS_EXPLANATION =
    "Sends which features you use with your phone model, Android version and app version. No account, no audio, no preset names."

/**
 * Privacy: the one switch for the anonymous usage statistics, off by default, at the bottom above "About &
 * licenses". [analytics] is the consent gate's state (null = this build has no app key: nothing is shown).
 * Flipping it closes or opens the gate first, then persists (AppGraph.setAnalytics).
 */
internal fun LazyListScope.privacySection(analytics: Boolean?, actions: MenuActions) {
    if (analytics == null) return
    item(key = "privacy-header") { SectionHeader("Privacy") }
    item(key = "privacy-body") {
        SwitchRow(ANALYTICS_SWITCH, analytics, actions.onAnalytics)
        Text(ANALYTICS_EXPLANATION, style = MaterialTheme.typography.bodySmall)
    }
}
