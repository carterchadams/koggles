package dev.koggles

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
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
import dev.koggles.audio.ConsentData
import dev.koggles.audio.Source
import dev.koggles.render.ViewerQr
import dev.koggles.render.VisualizerView
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import dev.koggles.settings.SettingsValues
import dev.koggles.ui.AudioErrorScreen
import dev.koggles.ui.MenuModel
import dev.koggles.ui.MenuOverlay
import dev.koggles.ui.consumeAllTouches
import dev.koggles.ui.isAudioError
import dev.koggles.ui.menu.MenuActions
import dev.koggles.ui.visualizerGestures
import kotlinx.coroutines.flow.first
import dev.koggles.ui.Explainer
import dev.koggles.ui.UnsupportedScreen
import dev.koggles.ui.isGles3Supported
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    internal var visualizerView: VisualizerView? = null
        private set

    private val audio get() = AppGraph.audio

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanent = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        audio.dispatch(AudioEvent.PermissionResult(granted, permanent))
    }

    /** Request id of the consent dialog in flight; survives activity recreation. */
    private var consentRequestId = 0L

    /** Viewer-profile change count seen before launching the QR scanner (-1 = none pending). */
    private var qrCountBeforeScan = -1

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val consent = if (result.resultCode == RESULT_OK && data != null) ConsentData(result.resultCode, data) else null
        audio.provideConsent(consentRequestId, consent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_CONSENT_REQUEST, consentRequestId)
        outState.putInt(KEY_QR_COUNT, qrCountBeforeScan)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentRequestId = savedInstanceState?.getLong(KEY_CONSENT_REQUEST) ?: 0L
        qrCountBeforeScan = savedInstanceState?.getInt(KEY_QR_COUNT, -1) ?: -1
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
        DebugHooks.install(this)
        setContent {
            val snapshot by audio.snapshot.collectAsStateWithLifecycle()
            val values by AppGraph.settings.values.collectAsStateWithLifecycle(SettingsValues())
            val menuOpen by AppGraph.menu.open.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                // The visualization stays attached; audio screens cover it completely.
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().testTag("visualizer-gestures").visualizerGestures(
                        singleTapEnabled = { values.singleTapNext },
                        onSingleTap = { AppGraph.playbackControls.next() },
                        onDoubleTap = { AppGraph.menu.toggle() },
                    ),
                )
                if (menuOpen) Menu()
                val state = snapshot.state
                if (state == AudioState.ShowingExplainer || state.isAudioError()) {
                    Box(Modifier.fillMaxSize().consumeAllTouches()) {
                        if (state == AudioState.ShowingExplainer) {
                            Explainer { audio.dispatch(AudioEvent.ExplainerAccepted) }
                        } else {
                            AudioErrorScreen(
                                state = state,
                                onRetry = { audio.dispatch(AudioEvent.Retry) },
                                onSwitchToHighQuality = { audio.dispatch(AudioEvent.SelectSource(Source.HighQuality)) },
                            )
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { promptForFolderOnce() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                audio.uiEffects.collect(::handleUiEffect)
            }
        }
        lifecycleScope.launch {
            AppGraph.renderCommands.state.map { it.goggles }.distinctUntilChanged().collect { applyOrientation(it) }
        }
    }

    /** Spec §4: the one-time "Choose folder" prompt is pending (shown first, highlighted). */
    private var highlightChooseFolder by mutableStateOf(false)

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        AppGraph.folderGrant.onTreePicked(uri)
        AppGraph.libraryRepo.refresh()
    }

    @androidx.compose.runtime.Composable
    private fun Menu() {
        val scope = rememberCoroutineScope()
        val now by AppGraph.playbackControls.nowPlaying.collectAsStateWithLifecycle()
        val values by AppGraph.settings.values.collectAsStateWithLifecycle(SettingsValues())
        val library by AppGraph.libraryRepo.state.collectAsStateWithLifecycle()
        val disabled by AppGraph.library.disabledPacks.collectAsStateWithLifecycle(emptySet())
        val favorites by AppGraph.library.favorites.collectAsStateWithLifecycle(emptySet())
        val skipped by AppGraph.library.skipped.collectAsStateWithLifecycle(emptySet())
        val snapshot by audio.snapshot.collectAsStateWithLifecycle()
        val silence by AppGraph.silenceHint.collectAsStateWithLifecycle()
        val actions = remember(scope) {
            MenuActions(
                playback = AppGraph.playbackControls,
                library = AppGraph.library,
                settings = AppGraph.settings,
                scope = scope,
                dispatchAudio = audio::dispatch,
                onChooseFolder = {
                    highlightChooseFolder = false
                    folderLauncher.launch(AppGraph.folderGrant.openTreeIntent())
                },
                onRescan = { AppGraph.libraryRepo.refresh() },
                onScanQr = ::scanViewerQr,
            )
        }
        MenuOverlay(
            MenuModel(now, values, library, disabled, favorites, skipped, snapshot, silence),
            actions,
            highlightChooseFolder,
            onClose = {
                highlightChooseFolder = false
                AppGraph.menu.close()
            },
        )
    }

    /** First run without a folder: open the menu once on the Library section (spec §4). */
    private suspend fun promptForFolderOnce() {
        val library = AppGraph.libraryRepo.state.first { it.userProbed }
        if (library.hasGrant || AppGraph.settings.values.first().folderPrompted) return
        AppGraph.settings.update { it.copy(folderPrompted = true) }
        highlightChooseFolder = true
        AppGraph.menu.show()
    }

    private fun handleUiEffect(effect: AudioEffect) {
        when (effect) {
            AudioEffect.RequestPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            AudioEffect.OpenAppSettings -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            AudioEffect.ShowExplainer -> Unit // rendered from AudioState.ShowingExplainer
            is AudioEffect.LaunchConsent -> {
                consentRequestId = effect.requestId
                consentLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
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
        if (qrCountBeforeScan >= 0) {
            // Back from the QR scanner: a newly saved profile resets the spacing offset.
            if (ViewerQr.changedCount() != qrCountBeforeScan) {
                lifecycleScope.launch { AppGraph.settings.update { it.copy(lensOffsetMm = 0f) } }
            }
            qrCountBeforeScan = -1
        }
        applyOrientation(AppGraph.renderCommands.state.value.goggles)
    }

    /** Opens the viewer-profile QR scanner (headset setup). */
    fun scanViewerQr() {
        qrCountBeforeScan = ViewerQr.changedCount()
        ViewerQr.scan(this)
    }

    private fun applyOrientation(goggles: Boolean) {
        requestedOrientation =
            if (goggles) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        DebugHooks.onKeyEvent(this, event) || super.dispatchKeyEvent(event)

    override fun onPause() {
        visualizerView?.onPause()
        super.onPause()
    }

    private companion object {
        const val KEY_CONSENT_REQUEST = "consentRequestId"
        const val KEY_QR_COUNT = "qrCountBeforeScan"
    }
}
