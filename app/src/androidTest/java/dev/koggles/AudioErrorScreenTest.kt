package dev.koggles

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.koggles.audio.AudioState
import dev.koggles.audio.FailReason
import dev.koggles.ui.AudioErrorScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioErrorScreenTest {
    @get:Rule val compose = createComposeRule()

    /** Capture failures cover the menu, so the screen itself must lead back to Standard audio. */
    @Test fun captureFailures_offerStandardAudio() {
        val reason = mutableStateOf(FailReason.ConsentDeclined)
        var switched = 0
        compose.setContent {
            AudioErrorScreen(AudioState.Failed(reason.value), onRetry = {}, onSwitchToHighQuality = {}, onSwitchToStandard = { switched++ })
        }
        for ((i, r) in listOf(FailReason.ConsentDeclined, FailReason.CaptureStartFailed, FailReason.CaptureLost).withIndex()) {
            reason.value = r
            compose.waitForIdle()
            compose.onNodeWithText("Use Standard audio").performClick()
            assertEquals("$r", i + 1, switched)
        }
    }
}
