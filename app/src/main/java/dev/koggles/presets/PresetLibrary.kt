package dev.koggles.presets

import dev.koggles.settings.orEmptyOnIoError
import dev.koggles.settings.persist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Per-pack and per-preset user marks (favourite, skipped, disabled pack). Packs are enabled unless listed in
 * [disabledPacks]. Keys are never pruned against the catalog: marks on vanished presets stay
 * dormant and apply again if the path reappears.
 */
class PresetLibrary(private val store: DataStore<Preferences>) {
    val disabledPacks: Flow<Set<String>> = set(DISABLED)
    val favorites: Flow<Set<String>> = set(FAVORITES)
    val skipped: Flow<Set<String>> = set(SKIPPED)

    suspend fun setPackEnabled(id: String, on: Boolean) = mark(DISABLED, id, !on)

    suspend fun toggleFavorite(key: String): Boolean = persist("favorites") {
        store.edit { p ->
            val cur = p[FAVORITES].orEmpty()
            p[FAVORITES] = if (key in cur) cur - key else cur + key
        }
    }

    suspend fun setSkipped(key: String, on: Boolean) = mark(SKIPPED, key, on)

    private suspend fun mark(k: Preferences.Key<Set<String>>, item: String, present: Boolean): Boolean = persist(k.name) {
        store.edit { p ->
            val cur = p[k].orEmpty()
            p[k] = if (present) cur + item else cur - item
        }
    }

    private fun set(k: Preferences.Key<Set<String>>): Flow<Set<String>> =
        store.data.orEmptyOnIoError().map { it[k].orEmpty() }.distinctUntilChanged()

    private companion object {
        val DISABLED = stringSetPreferencesKey("library.disabledPacks")
        val FAVORITES = stringSetPreferencesKey("library.favorites")
        val SKIPPED = stringSetPreferencesKey("library.skipped")
    }
}
