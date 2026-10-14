package dev.koggles.telemetry

import android.content.Context
import com.aptabase.Aptabase
import com.aptabase.InitOptions

/**
 * The vendor adapter, and the only file that imports the SDK. One [track] is one HTTP POST, run by the SDK on its
 * own executor; the body is the event name, our Int properties, and the SDK's system fields (device model, Android
 * version, app version and build number, language, debug flag, SDK version, timestamp, a session ID that changes
 * after an hour idle or when the process restarts). AptabaseTransportTest pins that list; the README discloses it.
 *
 * Built only by [ConsentGate] on the first consented event, so an install with the switch off never initialises
 * the SDK. [host] exists for the transport test (the SDK honours it only for a self-hosted "A-SH-" key).
 */
class AptabaseTelemetry(context: Context, key: String, host: String? = null) : Telemetry {
    init {
        Aptabase.instance.initialize(context.applicationContext, key, host?.let { InitOptions(host = it) })
    }

    override fun track(event: Event) {
        Aptabase.instance.trackEvent(event.name, event.props)
    }
}
