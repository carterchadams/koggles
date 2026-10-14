package dev.koggles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.koggles.ui.Explainer
import dev.koggles.ui.WARNING_TEXT
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
}
