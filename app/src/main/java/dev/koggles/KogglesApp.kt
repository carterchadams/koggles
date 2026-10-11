package dev.koggles

import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display

class KogglesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.nativeInit()
        // The SDK retains this context for the process and reads screen metrics through
        // getDisplay(), so it must be display-associated but must not be an Activity.
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        NativeBridge.cardboardInit(createDisplayContext(display))
        AppGraph.init(this)
        AppGraph.playback.start()
    }
}
