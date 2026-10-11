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

/** User settings (spec §4, §5.3, §6); defaults per the plan's Global Constraints. */
data class SettingsValues(
    val presetDurationSec: Int = 30,
    val softCut: Boolean = true,
    val blendSec: Double = 3.0,
    val hardCutsOnBeat: Boolean = false,
    val locked: Boolean = false,
    val interstitials: Boolean = true,
    val interstitialSec: Int = 4,
    val singleTapNext: Boolean = true,
    val goggles: Boolean = false,
    val lensOffsetMm: Float = 0f,
    val distortion: Boolean = true,
    val source: Source = Source.Standard,
    val explainerSeen: Boolean = false,
    val scope: Scope = Scope.AllEnabled,
    val lastPresetKey: String? = null,
)

val PRESET_DURATIONS = listOf(0, 15, 30, 60, 120)

/** Forces every ranged field into its legal set; applied on write (and on read, for hand-edited files). */
fun SettingsValues.clamped(): SettingsValues = copy(
    presetDurationSec = if (presetDurationSec in PRESET_DURATIONS) presetDurationSec else 30,
    blendSec = (blendSec.coerceIn(1.0, 10.0) * 2).roundToInt() / 2.0,
    interstitialSec = interstitialSec.coerceIn(2, 10),
    lensOffsetMm = (lensOffsetMm.coerceIn(-10f, 10f) * 2).roundToInt() / 2f,
)

/** Settings over the shared `koggles` preferences store. Keys are disjoint from [dev.koggles.presets.PresetLibrary]'s. */
class Settings(private val store: DataStore<Preferences>) {
    val values: Flow<SettingsValues> = store.data.map(::read).distinctUntilChanged()

    suspend fun update(f: (SettingsValues) -> SettingsValues) {
        store.edit { prefs ->
            val next = f(read(prefs)).clamped()
            prefs[K.presetDurationSec] = next.presetDurationSec
            prefs[K.softCut] = next.softCut
            prefs[K.blendSec] = next.blendSec
            prefs[K.hardCutsOnBeat] = next.hardCutsOnBeat
            prefs[K.locked] = next.locked
            prefs[K.interstitials] = next.interstitials
            prefs[K.interstitialSec] = next.interstitialSec
            prefs[K.singleTapNext] = next.singleTapNext
            prefs[K.goggles] = next.goggles
            prefs[K.lensOffsetMm] = next.lensOffsetMm
            prefs[K.distortion] = next.distortion
            prefs[K.source] = next.source.name
            prefs[K.explainerSeen] = next.explainerSeen
            prefs[K.scope] = next.scope.encode()
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
            interstitials = p[K.interstitials] ?: d.interstitials,
            interstitialSec = p[K.interstitialSec] ?: d.interstitialSec,
            singleTapNext = p[K.singleTapNext] ?: d.singleTapNext,
            goggles = p[K.goggles] ?: d.goggles,
            lensOffsetMm = p[K.lensOffsetMm] ?: d.lensOffsetMm,
            distortion = p[K.distortion] ?: d.distortion,
            source = p[K.source]?.let { n -> Source.entries.firstOrNull { it.name == n } } ?: d.source,
            explainerSeen = p[K.explainerSeen] ?: d.explainerSeen,
            scope = decodeScope(p[K.scope]),
            lastPresetKey = p[K.lastPresetKey],
        ).clamped()
    }

    private object K {
        val presetDurationSec = intPreferencesKey("settings.presetDurationSec")
        val softCut = booleanPreferencesKey("settings.softCut")
        val blendSec = doublePreferencesKey("settings.blendSec")
        val hardCutsOnBeat = booleanPreferencesKey("settings.hardCutsOnBeat")
        val locked = booleanPreferencesKey("settings.locked")
        val interstitials = booleanPreferencesKey("settings.interstitials")
        val interstitialSec = intPreferencesKey("settings.interstitialSec")
        val singleTapNext = booleanPreferencesKey("settings.singleTapNext")
        val goggles = booleanPreferencesKey("settings.goggles")
        val lensOffsetMm = floatPreferencesKey("settings.lensOffsetMm")
        val distortion = booleanPreferencesKey("settings.distortion")
        val source = stringPreferencesKey("settings.source")
        val explainerSeen = booleanPreferencesKey("settings.explainerSeen")
        val scope = stringPreferencesKey("settings.scope")
        val lastPresetKey = stringPreferencesKey("settings.lastPresetKey")
    }
}
