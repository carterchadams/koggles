package dev.koggles.render

import android.app.Activity
import android.content.Intent
import dev.koggles.NativeBridge

/**
 * Viewer-profile QR scanning. Launched from Kotlin with an Activity: the SDK's own
 * launcher calls startActivity on its process-wide context, which is not an Activity.
 */
object ViewerQr {
    private const val QR_ACTIVITY = "com.google.cardboard.sdk.QrCodeCaptureActivity"

    fun scan(activity: Activity) {
        activity.startActivity(Intent().setClassName(activity, QR_ACTIVITY))
    }

    /** Cardboard's saved-profile change counter. */
    fun changedCount(): Int = NativeBridge.viewerParamsChangedCount()
}
