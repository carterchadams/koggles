package dev.koggles

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import dev.koggles.audio.Source
import dev.koggles.telemetry.Event
import dev.koggles.telemetry.Telemetry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's event sources behind the consent gate, on the device: with the switch off nothing reaches the vendor;
 * with it on the UI paths produce exactly the events the README lists. The vendor is a recording fake installed
 * behind the gate (the gate itself is unit-tested in ConsentGateTest).
 */
@RunWith(AndroidJUnit4::class)
class TelemetryGateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        /** No system permission dialog over the activity (it hides the Compose hierarchy from the test). */
        @org.junit.BeforeClass @JvmStatic fun grantAudio() {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.uiAutomation.executeShellCommand(
                "pm grant ${inst.targetContext.packageName} android.permission.RECORD_AUDIO",
            ).close()
            // No first-run folder prompt (it opens the menu by itself): these tests open the menu themselves.
            runBlocking { AppGraph.settings.update { it.copy(folderPrompted = true, explainerSeen = true) } }
        }
    }

    /** The row's label is plain text; the Switch beside it is what toggles (MenuWidgets.SwitchRow tags it). */
    private fun switchFor(label: String) = compose.onNodeWithTag("switch:$label")

    private class Recording : Telemetry {
        val events = mutableListOf<Event>()
        override fun track(event: Event) { synchronized(events) { events += event } }
        fun names() = synchronized(events) { events.map { it.name } }
        fun last() = synchronized(events) { events.last() }
    }

    private val vendor = Recording()

    @Before fun setUp() {
        AppGraph.overrideTelemetryForTest(vendor)
        if (AppGraph.audio.snapshot.value.state == AudioState.ShowingExplainer) {
            compose.runOnUiThread { AppGraph.audio.dispatch(AudioEvent.ExplainerAccepted) }
        }
        compose.waitUntil(5_000) { AppGraph.audio.snapshot.value.state != AudioState.ShowingExplainer }
        runBlocking { AppGraph.settings.update { it.copy(goggles = false, wideView = 2, source = Source.Standard) } }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        compose.runOnUiThread { AppGraph.setAnalytics(false); AppGraph.menu.close() }
        AppGraph.renderCommands.setCalibration(false)
        runBlocking { AppGraph.settings.update { it.copy(goggles = false, wideView = 2) } }
        AppGraph.overrideTelemetryForTest(null)
    }

    /** A calibration that lasts long enough for the activity's collector to see it start (a StateFlow conflates). */
    private fun calibrate() {
        AppGraph.renderCommands.setCalibration(true)
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.calibration }
        compose.waitForIdle()
        AppGraph.renderCommands.setCalibration(false)
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onNodeWithTag("visualizer-gestures").performTouchInput { doubleClick() }
        compose.waitUntil(3_000) { AppGraph.menu.open.value }
        compose.waitForIdle()
    }

    @Test fun off_nothingReachesTheVendor() {
        compose.runOnUiThread { AppGraph.setAnalytics(false) }
        openMenu()
        switchFor("Goggles mode").performClick()
        compose.waitUntil(3_000) { AppGraph.renderCommands.state.value.goggles }
        calibrate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), vendor.names())
    }

    @Test fun on_menuTogglesAreReported() {
        compose.runOnUiThread { AppGraph.setAnalytics(true) }
        openMenu()
        switchFor("Goggles mode").performClick()
        compose.waitUntil(3_000) { vendor.names().contains("goggles_toggled") }
        assertEquals(mapOf("on" to 1), vendor.last().props)
        compose.onNodeWithTag("menu-list").performScrollToNode(hasText("Wide"))
        compose.onNodeWithText("Wide").performClick()
        compose.waitUntil(3_000) { vendor.names().contains("wide_view_set") }
        assertEquals(mapOf("mode" to 1), vendor.last().props)
    }

    @Test fun on_calibrationEndIsReported() {
        compose.runOnUiThread { AppGraph.setAnalytics(true) }
        calibrate()
        compose.waitUntil(3_000) { vendor.names().contains("calibration_finished") }
    }

    @Test fun on_startAndStopAreReported() {
        compose.runOnUiThread { AppGraph.setAnalytics(true) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)   // onStop: session_ended
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)   // onStart: app_started
        compose.waitUntil(3_000) { vendor.names().contains("app_started") }
        val names = vendor.names()
        assertTrue(names.toString(), names.indexOf("session_ended") < names.indexOf("app_started"))
        val started = synchronized(vendor.events) { vendor.events.first { it.name == "app_started" } }
        assertEquals(mapOf("source" to 0, "goggles" to 0, "wide_view" to 2), started.props)
        val ended = synchronized(vendor.events) { vendor.events.first { it.name == "session_ended" } }
        assertEquals(setOf("minutes"), ended.props.keys)
        assertEquals(0, ended.props.getValue("minutes")) // a session of seconds rounds to 0 minutes
    }
}
