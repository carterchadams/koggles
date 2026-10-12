package dev.koggles

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the scroll metric used by [OverlayScrollTest]: a slow (1 s) injected swipe on a plain
 * LazyColumn must move its reported scroll position. If this fails, the metric is invalid, not
 * the app.
 */
@RunWith(AndroidJUnit4::class)
class ControlScrollTest {

    @get:Rule val compose = createComposeRule()

    @Test fun control_slowSwipe_scrolls() {
        compose.setContent {
            LazyColumn(Modifier.testTag("control")) {
                items((1..300).toList()) { Text("Row $it") }
            }
        }
        val before = compose.scrollPosition("control")
        compose.onNodeWithTag("control").slowSwipeUp()
        compose.waitForIdle()
        val after = compose.scrollPosition("control")
        assertTrue("control list did not scroll: $before -> $after", after > before)
    }
}
