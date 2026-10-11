package dev.koggles

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.koggles.render.VisualizerView
import dev.koggles.ui.UnsupportedScreen
import dev.koggles.ui.isGles3Supported

class MainActivity : ComponentActivity() {
    internal var visualizerView: VisualizerView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugHooks.install(this)
        NativeBridge.cardboardInit(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!isGles3Supported(this)) {
            setContent { UnsupportedScreen() }
            return
        }
        visualizerView = VisualizerView(this).also { setContentView(it) }
    }

    override fun onResume() {
        super.onResume()
        visualizerView?.onResume()
    }

    override fun onPause() {
        visualizerView?.onPause()
        super.onPause()
    }
}
