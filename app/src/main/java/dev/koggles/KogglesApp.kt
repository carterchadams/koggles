package dev.koggles

import android.app.Application

class KogglesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
