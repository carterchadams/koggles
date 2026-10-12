package dev.koggles

import android.media.audiofx.Visualizer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import dev.koggles.audio.FailReason
import dev.koggles.audio.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Another client holding the output-mix Visualizer enabled makes Koggles' setup fail (seen in the
 * field with another visualizer app running). Koggles must say the visualizer is busy (never "unsupported") and start by itself
 * once the holder lets go, including after a background/foreground cycle.
 */
@RunWith(AndroidJUnit4::class)
class VisualizerContentionTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val main = InstrumentationRegistry.getInstrumentation()
    private var holder: Visualizer? = null

    companion object {
        private const val BUSY_TEXT = "Another app is using the audio visualizer. Koggles will start as soon as it's free."
        private const val UNSUPPORTED_TEXT = "This phone doesn't share its audio with visualizers."
        private val BUSY = AudioState.Failed(FailReason.VisualizerBusy)

        @BeforeClass @JvmStatic fun grantAudio() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.executeShellCommand(
                "pm grant ${inst.targetContext.packageName} android.permission.RECORD_AUDIO",
            ).close()
            runBlocking { AppGraph.settings.update { it.copy(folderPrompted = true, explainerSeen = true) } }
        }
    }

    @Before fun standardSource() {
        if (AppGraph.audio.snapshot.value.source != Source.Standard) {
            main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.SelectSource(Source.Standard)) }
        }
    }

    @After fun releaseHolder() = releaseHolderNow()

    private fun takeHolder() {
        holder = Visualizer(0).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1]
            enabled = true
        }
    }

    private fun releaseHolderNow() {
        holder?.let {
            runCatching { it.enabled = false }
            it.release()
        }
        holder = null
    }

    private fun waitForState(expected: AudioState, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = AppGraph.audio.snapshot.value.state
            if (s == AudioState.ShowingExplainer) main.runOnMainSync { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
            if (s == expected) return
            Thread.sleep(50)
        }
        throw AssertionError("expected $expected within $timeoutMs ms, was ${AppGraph.audio.snapshot.value.state}")
    }

    private fun assertBusyScreen() {
        compose.onNodeWithText(BUSY_TEXT).assertIsDisplayed()
        compose.onNodeWithText(UNSUPPORTED_TEXT).assertDoesNotExist()
    }

    @Test fun heldVisualizer_showsBusy_thenStartsWhenReleased() {
        takeHolder()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForState(BUSY, 5_000)
            assertBusyScreen()
            releaseHolderNow()
            waitForState(AudioState.Active, 6_000)
        }
    }

    @Test fun heldWhileBackgrounded_busyOnForeground_recoversAfterBackgroundRelease() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForState(AudioState.Active, 6_000)
            scenario.moveToState(Lifecycle.State.CREATED) // Standard stops
            takeHolder()
            scenario.moveToState(Lifecycle.State.RESUMED) // a fresh setup
            waitForState(BUSY, 5_000)
            assertBusyScreen()
            scenario.moveToState(Lifecycle.State.CREATED)
            releaseHolderNow()
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForState(AudioState.Active, 6_000)
        }
    }
}
