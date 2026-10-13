package dev.koggles.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import dev.koggles.R

/** The whole fade, in milliseconds: in over 0-400, held to 1200, out by 1600. */
const val SPLASH_MS = 1600

/**
 * The text logo, once per launch: it fades in, holds, and fades out over [SPLASH_MS], on an opaque
 * black field that covers the visualizer for the whole fade (its first frames are dark anyway), so a
 * captured pixel of the logo is alpha × logo and the ramps can be asserted. One frame-clock animation,
 * no `delay()`: under the test clock it costs nothing. Never consumes touches: no pointer input here, so
 * a tap or double tap during the fade reaches the gesture layer below. `rememberSaveable` keeps it from
 * replaying when the activity is restored from saved state; a fresh launch shows it again.
 *
 * The bitmap: `magick images/KogglesText.png -fuzz 8% -trim +repage -bordercolor black -border 24
 * -resize 800x -strip app/src/main/res/drawable-nodpi/koggles_logo.png` (brand-images phase).
 */
@Composable
fun LaunchSplash() {
    var done by rememberSaveable { mutableStateOf(false) }
    if (done) return
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            0f,
            keyframes {
                durationMillis = SPLASH_MS
                0f at 0 using LinearEasing
                1f at 400 using LinearEasing
                1f at 1200 using LinearEasing
                0f at SPLASH_MS
            },
        )
        done = true
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.koggles_logo),
            contentDescription = "Koggles",
            modifier = Modifier.fillMaxWidth(0.7f).graphicsLayer { this.alpha = alpha.value }.testTag("launch-logo"),
            contentScale = ContentScale.FillWidth,
        )
    }
}
