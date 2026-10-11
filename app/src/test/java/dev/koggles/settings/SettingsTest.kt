package dev.koggles.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
        assertEquals(true, v.interstitials)
        assertEquals(4, v.interstitialSec)
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
        s.update { it.copy(presetDurationSec = 45, blendSec = 0.2, lensOffsetMm = 11f, interstitialSec = 1) }
        var v = s.values.first()
        assertEquals(30, v.presetDurationSec)
        assertEquals(1.0, v.blendSec, 0.0)
        assertEquals(10f, v.lensOffsetMm, 0f)
        assertEquals(2, v.interstitialSec)

        s.update { it.copy(presetDurationSec = 120, blendSec = 3.26, lensOffsetMm = -0.3f, interstitialSec = 11) }
        v = s.values.first()
        assertEquals(120, v.presetDurationSec)
        assertEquals(3.5, v.blendSec, 0.0)
        assertEquals(-0.5f, v.lensOffsetMm, 0f)
        assertEquals(10, v.interstitialSec)
    }

    @Test fun persistsEveryField() = runBlocking {
        val s = Settings(store())
        val want = SettingsValues(
            presetDurationSec = 0, softCut = false, blendSec = 9.5, hardCutsOnBeat = true, locked = true,
            interstitials = false, interstitialSec = 7, singleTapNext = false, goggles = true,
            lensOffsetMm = -4.5f, distortion = false, source = Source.HighQuality, explainerSeen = true,
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
}
