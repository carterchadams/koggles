package dev.koggles.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.koggles.audio.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("x.preferences_pb") }

    @After fun tearDown() = scope.cancel()

    @Test fun defaultsMatchPlan() = runBlocking {
        val v = Settings(store()).values.first()
        assertEquals(30, v.presetDurationSec)
        assertEquals(true, v.softCut)
        assertEquals(3.0, v.blendSec, 0.0)
        assertEquals(false, v.hardCutsOnBeat)
        assertEquals(false, v.locked)
        assertEquals(true, v.singleTapNext)
        assertEquals(false, v.goggles)
        assertEquals(0f, v.lensOffsetMm, 0f)
        assertEquals(true, v.distortion)
        assertEquals(Source.Standard, v.source)
        assertEquals(false, v.explainerSeen)
        assertEquals(Scope.AllEnabled, v.scope)
        assertNull(v.lastPresetKey)
        assertEquals(SettingsValues(), v)
    }

    @Test fun folderPromptedDefaultsFalse() = runBlocking {
        val settings = Settings(store())
        assertEquals(false, settings.values.first().folderPrompted)
        settings.update { it.copy(folderPrompted = true) }
        assertEquals(true, settings.values.first().folderPrompted)
    }

    @Test fun scopeRoundTripsAllFour() {
        val all = listOf(
            Scope.AllEnabled,
            Scope.Favorites,
            Scope.PackOnly("user:My Pack"),
            Scope.Folder("bundled:cream", "Dancer/Whirl"),
        )
        assertEquals(listOf("all", "fav", "pack:user:My Pack", "folder:bundled:cream|Dancer/Whirl"), all.map { it.encode() })
        all.forEach { assertEquals(it, decodeScope(it.encode())) }
    }

    @Test fun garbageScopeDecodesToAll() {
        listOf(null, "", "nope", "pack:", "folder:x", "folder:|y", "FAV").forEach {
            assertEquals("decode($it)", Scope.AllEnabled, decodeScope(it))
        }
    }

    @Test fun clampsOnWrite() = runBlocking {
        val s = Settings(store())
        s.update { it.copy(presetDurationSec = 45, blendSec = 0.2, lensOffsetMm = 41f) } // the range is -10..40 since wide view
        var v = s.values.first()
        assertEquals(30, v.presetDurationSec)
        assertEquals(1.0, v.blendSec, 0.0)
        assertEquals(40f, v.lensOffsetMm, 0f)

        s.update { it.copy(presetDurationSec = 120, blendSec = 3.26, lensOffsetMm = -0.3f) }
        v = s.values.first()
        assertEquals(120, v.presetDurationSec)
        assertEquals(3.5, v.blendSec, 0.0)
        assertEquals(-0.5f, v.lensOffsetMm, 0f)
    }

    @Test fun persistsEveryField() = runBlocking {
        val s = Settings(store())
        val want = SettingsValues(
            presetDurationSec = 0, softCut = false, blendSec = 9.5, hardCutsOnBeat = true, locked = true,
            singleTapNext = false, goggles = true,
            lensOffsetMm = -4.5f, distortion = false, source = Source.HighQuality, explainerSeen = true,
            wideView = 1, lensOffsetWideMm = 27.5f, lensOffsetWideWindowMm = 15.5f,
            wideSpacingManual = true, wideWindowSpacingManual = true,
            scope = Scope.Folder("user:p", "a/b"), lastPresetKey = "user:p/a/b/x.milk",
        )
        s.update { want }
        assertEquals(want, s.values.first())
        s.update { it.copy(lastPresetKey = null) }
        assertNull(s.values.first().lastPresetKey)
    }

    @Test fun corruptPreferencesFile_recoversToDefaults() = runBlocking {
        val file = tmp.root.resolve("corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03)) // truncated/garbage protobuf
        val store = KogglesStore.create(scope) { file }
        assertEquals(SettingsValues(), Settings(store).values.first())
    }

    @Test fun readIOException_yieldsDefaults() = runBlocking {
        val failing = object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> =
                kotlinx.coroutines.flow.flow { throw java.io.IOException("disk") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw java.io.IOException("disk")
        }
        assertEquals(SettingsValues(), Settings(failing).values.first())
    }

    @Test fun folderScopeWithPipesAndBackslashes_roundTrips() {
        for (scope in listOf(
            Scope.Folder("user:A|B", "Dancer"),
            Scope.Folder("user:A\\|", "x|y\\z"),
            Scope.PackOnly("user:p|q"),
        )) assertEquals(scope, decodeScope(scope.encode()))
    }

    @Test fun writeIOException_isContainedAndReported() = runBlocking {
        val failing = object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> =
                kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw java.io.IOException("disk full")
        }
        assertEquals(false, Settings(failing).update { it.copy(explainerSeen = true) })
        assertEquals(false, dev.koggles.presets.PresetLibrary(failing).setSkipped("k", true))
    }

    // ---- Wide view (the author, 2026-10-13: separate spacing per mode; computed defaults unless set by hand) ----

    @Test fun wideView_newInstall_defaultsToWideWindow() = runBlocking {
        val v = Settings(store()).values.first()
        assertEquals(2, v.wideView)
        assertEquals(0f, v.lensOffsetWideMm, 0f)
        assertEquals(0f, v.lensOffsetWideWindowMm, 0f)
        assertEquals(false, v.wideSpacingManual)
        assertEquals(false, v.wideWindowSpacingManual)
    }

    /** An install that ran before this version (the explainer flag exists) keeps today's view until it chooses. */
    @Test fun wideView_existingInstall_staysOff() = runBlocking {
        val st = store()
        st.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey("settings.explainerSeen")] = true }
        assertEquals(0, Settings(st).values.first().wideView)
    }

    @Test fun wideView_savedValueWins() = runBlocking {
        val st = store()
        st.edit {
            it[androidx.datastore.preferences.core.booleanPreferencesKey("settings.explainerSeen")] = true
            it[androidx.datastore.preferences.core.intPreferencesKey("settings.wideView")] = 1
        }
        assertEquals(1, Settings(st).values.first().wideView)
    }

    @Test fun effectiveLensOffset_followsTheMode() {
        val v = SettingsValues(lensOffsetMm = 1f, lensOffsetWideMm = 22f, lensOffsetWideWindowMm = 8.5f)
        assertEquals(1f, v.copy(wideView = 0).effectiveLensOffsetMm(), 0f)
        assertEquals(22f, v.copy(wideView = 1).effectiveLensOffsetMm(), 0f)
        assertEquals(8.5f, v.copy(wideView = 2).effectiveLensOffsetMm(), 0f)
    }

    @Test fun spacingClampsToMinus10Plus40_inHalfSteps() = runBlocking {
        val s = Settings(store())
        s.update { it.copy(lensOffsetMm = 41f, lensOffsetWideMm = -11f, lensOffsetWideWindowMm = 15.26f, wideView = 7) }
        val v = s.values.first()
        assertEquals(40f, v.lensOffsetMm, 0f)
        assertEquals(-10f, v.lensOffsetWideMm, 0f)
        assertEquals(15.5f, v.lensOffsetWideWindowMm, 0f)
        assertEquals(2, v.wideView)
    }

    /** The slider and the calibration edit the current mode's value; a wide mode touched by hand is never recomputed. */
    @Test fun withLensOffsetForCurrentMode_editsThatMode_andMarksWideModesManual() {
        val v = SettingsValues(lensOffsetMm = 1f, lensOffsetWideMm = 2f, lensOffsetWideWindowMm = 3f)
        val off = v.copy(wideView = 0).withLensOffsetForCurrentMode(5f)
        assertEquals(5f, off.lensOffsetMm, 0f); assertEquals(false, off.wideSpacingManual); assertEquals(false, off.wideWindowSpacingManual)
        val wide = v.copy(wideView = 1).withLensOffsetForCurrentMode(5f)
        assertEquals(5f, wide.lensOffsetWideMm, 0f); assertEquals(1f, wide.lensOffsetMm, 0f); assertEquals(true, wide.wideSpacingManual); assertEquals(false, wide.wideWindowSpacingManual)
        val win = v.copy(wideView = 2).withLensOffsetForCurrentMode(5f)
        assertEquals(5f, win.lensOffsetWideWindowMm, 0f); assertEquals(true, win.wideWindowSpacingManual); assertEquals(false, win.wideSpacingManual)
    }

    @Test fun withSpacingStep_stepsTheCurrentMode_withinTheRange() = runBlocking {
        val s = Settings(store())
        s.update { it.copy(wideView = 2, lensOffsetWideWindowMm = 39.5f) }
        s.update { it.withSpacingStep(+0.5f) }
        assertEquals(40f, s.values.first().lensOffsetWideWindowMm, 0f)
        s.update { it.withSpacingStep(+0.5f) }
        assertEquals(40f, s.values.first().lensOffsetWideWindowMm, 0f)
        assertEquals(true, s.values.first().wideWindowSpacingManual)
        s.update { it.copy(wideView = 0, lensOffsetMm = -10f).withSpacingStep(-0.5f) }
        assertEquals(-10f, s.values.first().lensOffsetMm, 0f)
    }
}
