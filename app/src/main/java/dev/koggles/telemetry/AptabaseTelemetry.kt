package dev.koggles.telemetry

import android.content.Context
import com.aptabase.Aptabase
import com.aptabase.InitOptions
import com.aptabase.TrackingMode
import dev.koggles.BuildConfig

/**
 * The vendor adapter, and the only file that imports the SDK. One [track] is one HTTP POST, run by the SDK on its
 * own executor; the body is the event name, our Int properties, and the SDK's system fields (device model, Android
 * version, app version and build number, language, debug flag, SDK version, timestamp, a session ID that changes
 * after an hour idle or when the process restarts). AptabaseTransportTest pins that list; the README discloses it.
 *
 * Built only by [ConsentGate] on the first consented event, so an install with the switch off never initialises
 * the SDK. [host] exists for the transport test (the SDK honours it only for a self-hosted "A-SH-" key).
 *
 * [debug] is the tracking mode, passed explicitly rather than left to the SDK's reading of the build's debuggable
 * flag: only an unsuffixed release build is a real install (BuildConfig.APTABASE_DEBUG, set per build type), so
 * development and device-experiment use lands in the dashboard's separate debug view, never in the release numbers.
 */
class AptabaseTelemetry(context: Context, key: String, host: String? = null, debug: Boolean = BuildConfig.APTABASE_DEBUG) : Telemetry {
    init {
        val mode = if (debug) TrackingMode.asDebug else TrackingMode.asRelease
        Aptabase.instance.initialize(context.applicationContext, key, InitOptions(host = host, trackingMode = mode))
    }

    override fun track(event: Event) {
        Aptabase.instance.trackEvent(event.name, event.props)
    }
}
