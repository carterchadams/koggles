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
    private var visualizer: VisualizerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugHooks.install(this)
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
        visualizer = VisualizerView(this).also { setContentView(it) }
    }

    override fun onResume() {
        super.onResume()
        visualizer?.onResume()
    }

    override fun onPause() {
        visualizer?.onPause()
        super.onPause()
    }
}
