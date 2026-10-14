package dev.koggles.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.koggles.audio.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** User settings. The defaults are product decisions, not placeholders; tests rely on them. */
data class SettingsValues(
    val presetDurationSec: Int = 30,
    val softCut: Boolean = true,
    val blendSec: Double = 3.0,
    val hardCutsOnBeat: Boolean = false,
    val locked: Boolean = false,
    val singleTapNext: Boolean = true,
    val goggles: Boolean = false,
    /** Lens spacing offset in normal view, mm (the viewer profile's inter-lens distance plus this). */
    val lensOffsetMm: Float = 0f,
    val distortion: Boolean = true,
    /**
     * Wide view (the author, 2026-10-13): 0 normal, 1 wide (the viewer profile's field of view opened to the screen
     * edges), 2 wide + a different window of the frame per eye. Each wide mode has its own spacing offset; those are
     * computed from the profile, the screen and the normal spacing unless the user has set them by hand (the flags).
     */
    val wideView: Int = 2,
    val lensOffsetWideMm: Float = 0f,
    val lensOffsetWideWindowMm: Float = 0f,
    val wideSpacingManual: Boolean = false,
    val wideWindowSpacingManual: Boolean = false,
    /** The inputs (display, normal spacing, viewer profile) the wide defaults were last computed for; null = never. */
    val wideDefaultsFor: String? = null,
    val source: Source = Source.Standard,
    val explainerSeen: Boolean = false,
    val scope: Scope = Scope.AllEnabled,
    val lastPresetKey: String? = null,
    /** The first-run "Choose folder" prompt has been shown; it is shown once per install. */
    val folderPrompted: Boolean = false,
)

val PRESET_DURATIONS = listOf(0, 15, 30, 60, 120)

/** Lens spacing offsets, mm, in 0.5 mm steps. Wide view on a wide phone needs about +28 (the research note's derivation). */
val LENS_OFFSET_RANGE = -10f..40f

private fun Float.halfMm(): Float = (coerceIn(LENS_OFFSET_RANGE.start, LENS_OFFSET_RANGE.endInclusive) * 2).roundToInt() / 2f

/** The spacing offset the current view uses. */
fun SettingsValues.effectiveLensOffsetMm(): Float = when (wideView) {
    1 -> lensOffsetWideMm
    2 -> lensOffsetWideWindowMm
    else -> lensOffsetMm
}

/**
 * Sets the current view's spacing offset. A wide mode set this way counts as set by hand and is never recomputed
 * again; the normal mode has no computed default.
 */
fun SettingsValues.withLensOffsetForCurrentMode(mm: Float): SettingsValues = when (wideView) {
    1 -> copy(lensOffsetWideMm = mm.halfMm(), wideSpacingManual = true)
    2 -> copy(lensOffsetWideWindowMm = mm.halfMm(), wideWindowSpacingManual = true)
    else -> copy(lensOffsetMm = mm.halfMm())
}

/** One calibration step (volume up +0.5 mm, volume down −0.5 mm) on the current view, within the range. */
fun SettingsValues.withSpacingStep(deltaMm: Float): SettingsValues = withLensOffsetForCurrentMode(effectiveLensOffsetMm() + deltaMm)

/** Forces every ranged field into its legal set; applied on write (and on read, for hand-edited files). */
fun SettingsValues.clamped(): SettingsValues = copy(
    presetDurationSec = if (presetDurationSec in PRESET_DURATIONS) presetDurationSec else 30,
    blendSec = (blendSec.coerceIn(1.0, 10.0) * 2).roundToInt() / 2.0,
    lensOffsetMm = lensOffsetMm.halfMm(),
    lensOffsetWideMm = lensOffsetWideMm.halfMm(),
    lensOffsetWideWindowMm = lensOffsetWideWindowMm.halfMm(),
    wideView = wideView.coerceIn(0, 2),
)

/** Settings over the shared `koggles` preferences store. Keys are disjoint from [dev.koggles.presets.PresetLibrary]'s. */
class Settings(private val store: DataStore<Preferences>) {
    val values: Flow<SettingsValues> = store.data.orEmptyOnIoError().map(::read).distinctUntilChanged()

    /** Applies [f] (clamped) and persists it; returns false if the write failed. */
    suspend fun update(f: (SettingsValues) -> SettingsValues): Boolean = persist("settings") {
        store.edit { prefs ->
            val next = f(read(prefs)).clamped()
            prefs[K.presetDurationSec] = next.presetDurationSec
            prefs[K.softCut] = next.softCut
            prefs[K.blendSec] = next.blendSec
            prefs[K.hardCutsOnBeat] = next.hardCutsOnBeat
            prefs[K.locked] = next.locked
            prefs[K.singleTapNext] = next.singleTapNext
            prefs[K.goggles] = next.goggles
            prefs[K.lensOffsetMm] = next.lensOffsetMm
            prefs[K.distortion] = next.distortion
            prefs[K.wideView] = next.wideView
            prefs[K.lensOffsetWideMm] = next.lensOffsetWideMm
            prefs[K.lensOffsetWideWindowMm] = next.lensOffsetWideWindowMm
            prefs[K.wideSpacingManual] = next.wideSpacingManual
            prefs[K.wideWindowSpacingManual] = next.wideWindowSpacingManual
            val wideFor = next.wideDefaultsFor
            if (wideFor == null) prefs.remove(K.wideDefaultsFor) else prefs[K.wideDefaultsFor] = wideFor
            prefs[K.source] = next.source.name
            prefs[K.explainerSeen] = next.explainerSeen
            prefs[K.scope] = next.scope.encode()
            prefs[K.folderPrompted] = next.folderPrompted
            val last = next.lastPresetKey
            if (last == null) prefs.remove(K.lastPresetKey) else prefs[K.lastPresetKey] = last
        }
    }

    private fun read(p: Preferences): SettingsValues {
        val d = SettingsValues()
        return SettingsValues(
            presetDurationSec = p[K.presetDurationSec] ?: d.presetDurationSec,
            softCut = p[K.softCut] ?: d.softCut,
            blendSec = p[K.blendSec] ?: d.blendSec,
            hardCutsOnBeat = p[K.hardCutsOnBeat] ?: d.hardCutsOnBeat,
            locked = p[K.locked] ?: d.locked,
            singleTapNext = p[K.singleTapNext] ?: d.singleTapNext,
            goggles = p[K.goggles] ?: d.goggles,
            lensOffsetMm = p[K.lensOffsetMm] ?: d.lensOffsetMm,
            distortion = p[K.distortion] ?: d.distortion,
            // An install from before wide view (its explainer flag exists) keeps the normal view until it chooses.
            wideView = p[K.wideView] ?: if (p.contains(K.explainerSeen)) 0 else d.wideView,
            lensOffsetWideMm = p[K.lensOffsetWideMm] ?: d.lensOffsetWideMm,
            lensOffsetWideWindowMm = p[K.lensOffsetWideWindowMm] ?: d.lensOffsetWideWindowMm,
            wideSpacingManual = p[K.wideSpacingManual] ?: d.wideSpacingManual,
            wideWindowSpacingManual = p[K.wideWindowSpacingManual] ?: d.wideWindowSpacingManual,
            wideDefaultsFor = p[K.wideDefaultsFor],
            source = p[K.source]?.let { n -> Source.entries.firstOrNull { it.name == n } } ?: d.source,
            explainerSeen = p[K.explainerSeen] ?: d.explainerSeen,
            scope = decodeScope(p[K.scope]),
            lastPresetKey = p[K.lastPresetKey],
            folderPrompted = p[K.folderPrompted] ?: d.folderPrompted,
        ).clamped()
    }

    private object K {
        val presetDurationSec = intPreferencesKey("settings.presetDurationSec")
        val softCut = booleanPreferencesKey("settings.softCut")
        val blendSec = doublePreferencesKey("settings.blendSec")
        val hardCutsOnBeat = booleanPreferencesKey("settings.hardCutsOnBeat")
        val locked = booleanPreferencesKey("settings.locked")
        // settings.interstitials and settings.interstitialSec were removed with the feature (2026-10-13);
        // a stale value in an existing install is ignored.
        val singleTapNext = booleanPreferencesKey("settings.singleTapNext")
        val goggles = booleanPreferencesKey("settings.goggles")
        val lensOffsetMm = floatPreferencesKey("settings.lensOffsetMm")
        val distortion = booleanPreferencesKey("settings.distortion")
        val wideView = intPreferencesKey("settings.wideView")
        val lensOffsetWideMm = floatPreferencesKey("settings.lensOffsetWideMm")
        val lensOffsetWideWindowMm = floatPreferencesKey("settings.lensOffsetWideWindowMm")
        val wideSpacingManual = booleanPreferencesKey("settings.wideSpacingManual")
        val wideWindowSpacingManual = booleanPreferencesKey("settings.wideWindowSpacingManual")
        val wideDefaultsFor = stringPreferencesKey("settings.wideDefaultsFor")
        val source = stringPreferencesKey("settings.source")
        val explainerSeen = booleanPreferencesKey("settings.explainerSeen")
        val scope = stringPreferencesKey("settings.scope")
        val lastPresetKey = stringPreferencesKey("settings.lastPresetKey")
        val folderPrompted = booleanPreferencesKey("settings.folderPrompted")
    }
}
