package dev.koggles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.koggles.audio.AudioEffect
import dev.koggles.audio.AudioEvent
import dev.koggles.audio.AudioState
import dev.koggles.audio.Source
import dev.koggles.render.VisualizerView
import dev.koggles.ui.AudioErrorScreen
import dev.koggles.ui.Explainer
import dev.koggles.ui.UnsupportedScreen
import dev.koggles.ui.isGles3Supported
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    internal var visualizerView: VisualizerView? = null
        private set

    private val audio get() = AppGraph.audio

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanent = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        audio.dispatch(AudioEvent.PermissionResult(granted, permanent))
    }

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
        val view = VisualizerView(this).also { visualizerView = it }
        setContent {
            val snapshot by audio.snapshot.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                // The visualization stays attached; audio screens cover it completely.
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                when (val state = snapshot.state) {
                    AudioState.ShowingExplainer -> Explainer { audio.dispatch(AudioEvent.ExplainerAccepted) }
                    else -> AudioErrorScreen(
                        state = state,
                        onRetry = { audio.dispatch(AudioEvent.Retry) },
                        onSwitchToHighQuality = { audio.dispatch(AudioEvent.SelectSource(Source.HighQuality)) },
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                audio.uiEffects.collect(::handleUiEffect)
            }
        }
    }

    private fun handleUiEffect(effect: AudioEffect) {
        when (effect) {
            AudioEffect.RequestPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            AudioEffect.OpenAppSettings -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            AudioEffect.ShowExplainer -> Unit // rendered from AudioState.ShowingExplainer
            is AudioEffect.LaunchConsent -> Unit // Task 8
            else -> Unit
        }
    }

    override fun onStart() {
        super.onStart()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        audio.dispatch(AudioEvent.ActivityStarted(granted))
    }

    override fun onStop() {
        audio.dispatch(AudioEvent.ActivityStopped)
        super.onStop()
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
