package dev.koggles

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import dev.koggles.settings.SettingsValues
import dev.koggles.settings.withSpacingStep
import dev.koggles.ui.AudioErrorScreen
import dev.koggles.ui.MenuModel
import dev.koggles.ui.MenuOverlay
import dev.koggles.ui.orientationFor
import dev.koggles.ui.blockTouchesBelow
import dev.koggles.ui.isAudioError
import dev.koggles.ui.menu.MenuActions
import dev.koggles.ui.visualizerGestures
import kotlinx.coroutines.flow.first
import dev.koggles.ui.Explainer
import dev.koggles.telemetry.Event
import dev.koggles.ui.KogglesTheme
import dev.koggles.ui.LaunchSplash
import dev.koggles.ui.UnsupportedScreen
import dev.koggles.ui.isGles3Supported
import kotlinx.coroutines.flow.combine
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
            setContent { KogglesTheme { UnsupportedScreen() } }
            return
        }
        val view = VisualizerView(this).also { visualizerView = it }
        DebugHooks.install(this)
        setContent {
          KogglesTheme {
            val snapshot by audio.snapshot.collectAsStateWithLifecycle()
            val values by AppGraph.settings.values.collectAsStateWithLifecycle(SettingsValues())
            val menuOpen by AppGraph.menu.open.collectAsStateWithLifecycle()
            val calibrating by AppGraph.renderCommands.state.map { it.calibration }
                .collectAsStateWithLifecycle(AppGraph.renderCommands.state.value.calibration)
            Box(Modifier.fillMaxSize()) {
                // The visualization stays attached; audio screens cover it completely.
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                // Calibrating: a double tap ends it (a Cardboard trigger is a tap), a single tap does nothing
                // (the screen is easily touched while the phone is being inserted into the mount).
                Box(
                    Modifier.fillMaxSize().testTag("visualizer-gestures").visualizerGestures(
                        singleTapEnabled = { calibrating || values.singleTapNext },
                        onSingleTap = { if (!calibrating) AppGraph.playbackControls.next() },
                        onDoubleTap = { if (calibrating) AppGraph.renderCommands.setCalibration(false) else AppGraph.menu.toggle() },
                    ),
                )
                if (menuOpen) Menu()
                val state = snapshot.state
                if (state == AudioState.ShowingExplainer || state.isAudioError()) {
                    Box(Modifier.fillMaxSize().blockTouchesBelow()) {
                        if (state == AudioState.ShowingExplainer) {
                            val analytics by AppGraph.telemetry.enabled.collectAsStateWithLifecycle()
                            Explainer(
                                analytics = if (AppGraph.analyticsAvailable) analytics else null,
                                onAnalyticsChange = AppGraph::setAnalytics,
                            ) { audio.dispatch(AudioEvent.ExplainerAccepted) }
                        } else {
                            AudioErrorScreen(
                                state = state,
                                onRetry = { audio.dispatch(AudioEvent.Retry) },
                                onSwitchToHighQuality = { audio.dispatch(AudioEvent.SelectSource(Source.HighQuality)) },
                                onSwitchToStandard = { audio.dispatch(AudioEvent.SelectSource(Source.Standard)) },
                            )
                        }
                    }
                }
                LaunchSplash() // the logo fade, over everything, once per launch
            }
          }
        }
        lifecycleScope.launch { promptForFolderOnce() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                audio.uiEffects.collect(::handleUiEffect)
            }
        }
        // Spacing calibration: the system Back ends it (the back dispatcher, which is how Back arrives with
        // predictive back on Android 16 and this target; KEYCODE_BACK below covers devices that still send it).
        val endCalibration = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { AppGraph.renderCommands.setCalibration(false) }
        }
        onBackPressedDispatcher.addCallback(this, endCalibration)
        lifecycleScope.launch {
            AppGraph.renderCommands.state.map { it.calibration }.distinctUntilChanged().collect { calibrating ->
                if (endCalibration.isEnabled && !calibrating) AppGraph.telemetry.track(Event.CalibrationFinished)
                endCalibration.isEnabled = calibrating
            }
        }
        lifecycleScope.launch {
            // One writer of requestedOrientation: the mode and the menu together (menu open = portrait).
            combine(AppGraph.renderCommands.state.map { it.goggles }, AppGraph.menu.open, ::orientationFor)
                .distinctUntilChanged().collect { requestedOrientation = it }
        }
    }

    /** The one-time "Choose folder" prompt is pending (shown first, highlighted). */
    private var highlightChooseFolder by mutableStateOf(false)

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        AppGraph.folderPicker.picked(uri.toString()) // grant IPC off main; failures reach Library
    }

    @androidx.compose.runtime.Composable
    private fun Menu() {
        val now by AppGraph.playbackControls.nowPlaying.collectAsStateWithLifecycle()
        val values by AppGraph.settings.values.collectAsStateWithLifecycle(SettingsValues())
        val library by AppGraph.libraryRepo.state.collectAsStateWithLifecycle()
        val disabled by AppGraph.library.disabledPacks.collectAsStateWithLifecycle(emptySet())
        val favorites by AppGraph.library.favorites.collectAsStateWithLifecycle(emptySet())
        val skipped by AppGraph.library.skipped.collectAsStateWithLifecycle(emptySet())
        val snapshot by audio.snapshot.collectAsStateWithLifecycle()
        val silence by AppGraph.silenceHint.collectAsStateWithLifecycle()
        val analytics by AppGraph.telemetry.enabled.collectAsStateWithLifecycle()
        val actions = remember {
            MenuActions(
                playback = AppGraph.playbackControls,
                library = AppGraph.library,
                settings = AppGraph.settings,
                // Not the composition's scope: a committed setting must survive closing the menu.
                scope = AppGraph.appScope,
                dispatchAudio = audio::dispatch,
                onChooseFolder = {
                    highlightChooseFolder = false
                    folderLauncher.launch(AppGraph.folderGrant.openTreeIntent())
                },
                onRescan = { AppGraph.libraryRepo.refresh() },
                onScanQr = ::scanViewerQr,
                track = { AppGraph.telemetry.track(it) },
                onAnalytics = AppGraph::setAnalytics,
                onCalibrateSpacing = {
                    AppGraph.renderCommands.setCalibration(true)
                    AppGraph.menu.close()
                },
            )
        }
        MenuOverlay(
            MenuModel(
                now, values, library, disabled, favorites, skipped, snapshot, silence,
                analytics = if (AppGraph.analyticsAvailable) analytics else null,
            ),
            actions,
            highlightChooseFolder,
            onClose = {
                highlightChooseFolder = false
                AppGraph.menu.close()
            },
        )
    }

    /** First run without a folder: open the menu once on the Library section, and never again. */
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
        sessionStartedAt = android.os.SystemClock.elapsedRealtime()
        val render = AppGraph.renderCommands.state.value
        AppGraph.telemetry.track(Event.AppStarted(audio.snapshot.value.source, render.goggles, render.lens.wideView))
        // The folder may have been deleted or revoked while we were away; playback must fall back to bundled.
        AppGraph.appScope.launch { AppGraph.libraryRepo.revalidate() }
    }

    /**
     * Spacing calibration keys: while the bullseyes are up, volume up/down step the current view's spacing by
     * 0.5 mm (consumed, so the volume does not change) and Back, or a double tap on the screen, ends the
     * calibration. Otherwise every key is the system's.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DebugHooks.onKeyEvent(this, event)) return true
        if (!AppGraph.renderCommands.state.value.calibration) return super.dispatchKeyEvent(event)
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 0.5f
            KeyEvent.KEYCODE_VOLUME_DOWN -> -0.5f
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) AppGraph.renderCommands.setCalibration(false)
                return true
            }
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            lifecycleScope.launch { AppGraph.settings.update { it.withSpacingStep(step) } }
        }
        return true
    }

    /** Set in onStart; the session length reported in onStop is rounded to whole minutes. */
    private var sessionStartedAt = 0L

    override fun onStop() {
        audio.dispatch(AudioEvent.ActivityStopped)
        val seconds = (android.os.SystemClock.elapsedRealtime() - sessionStartedAt) / 1000
        AppGraph.telemetry.track(Event.SessionEnded(minutes = ((seconds + 30) / 60).toInt()))
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        visualizerView?.onResume()
        if (qrCountBeforeScan >= 0) {
            // Back from the QR scanner: a newly saved profile resets the spacing offset.
            if (ViewerQr.changedCount() != qrCountBeforeScan) {
                lifecycleScope.launch { AppGraph.settings.update { it.copy(lensOffsetMm = 0f) } }
                AppGraph.telemetry.track(Event.ViewerQrScanned)
            }
            qrCountBeforeScan = -1
        }
        // A new viewer profile (or a new display) changes the wide views' default spacing.
        AppGraph.wideSpacingDefaults.recheck()
        // Back from another activity (the QR scanner): the same rule, menu state included.
        requestedOrientation = orientationFor(AppGraph.renderCommands.state.value.goggles, AppGraph.menu.open.value)
    }

    /** Opens the viewer-profile QR scanner (headset setup). */
    fun scanViewerQr() {
        qrCountBeforeScan = ViewerQr.changedCount()
        ViewerQr.scan(this)
    }

    override fun onPause() {
        visualizerView?.onPause()
        super.onPause()
    }

    private companion object {
        const val KEY_CONSENT_REQUEST = "consentRequestId"
        const val KEY_QR_COUNT = "qrCountBeforeScan"
    }
}
