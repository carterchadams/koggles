package dev.koggles.ui

import android.content.pm.ActivityInfo

/**
 * The activity's requested orientation. The menu is portrait whenever it is open, in both modes
 * (the author, 2026-10-13: a phone in a headset mount still shows its settings upright); closed,
 * goggles mode is landscape (either way up) and normal mode follows the device.
 */
fun orientationFor(goggles: Boolean, menuOpen: Boolean): Int = when {
    menuOpen -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    goggles -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
