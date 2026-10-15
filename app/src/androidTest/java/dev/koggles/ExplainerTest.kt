package dev.koggles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.koggles.ui.Explainer
import dev.koggles.ui.WARNING_TEXT
import dev.koggles.ui.menu.ANALYTICS_EXPLANATION
import dev.koggles.ui.menu.ANALYTICS_SWITCH
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplainerTest {
    @get:Rule val compose = createComposeRule()

    /** When the warning and the permission text do not fit (landscape, large fonts), the screen scrolls to Continue. */
    @Test fun continue_isReachableByScrolling_whenTheTextDoesNotFit() {
        var continued = 0
        compose.setContent {
            Box(Modifier.width(300.dp).height(160.dp)) { Explainer { continued++ } }
        }
        compose.onNodeWithText(WARNING_TEXT).assertIsDisplayed()
        val button = compose.onNodeWithText("Continue")
        button.assertIsNotDisplayed()
        button.performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, continued)
    }

    /** The analytics switch is offered on the first screen, off, with its explanation; Continue works untouched. */
    @Test fun analyticsSwitch_isShownOff_andReportsTheChoice() {
        var chosen: Boolean? = null
        var continued = 0
        compose.setContent { Explainer(analytics = false, onAnalyticsChange = { chosen = it }) { continued++ } }
        compose.onNodeWithText(ANALYTICS_EXPLANATION).assertIsDisplayed()
        val switch = compose.onNodeWithTag("switch:$ANALYTICS_SWITCH").assertIsDisplayed()
        switch.assertIsOff()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, continued)
        assertNull(chosen)
        switch.performClick()
        assertEquals(true, chosen)
    }

    /** A build without an app key (plain CI, forks) offers no switch at all. */
    @Test fun analyticsSwitch_isAbsentWhenUnavailable() {
        compose.setContent { Explainer(analytics = null) {} }
        compose.onNodeWithTag("switch:$ANALYTICS_SWITCH").assertDoesNotExist()
        compose.onNodeWithText(ANALYTICS_EXPLANATION).assertDoesNotExist()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }
}
