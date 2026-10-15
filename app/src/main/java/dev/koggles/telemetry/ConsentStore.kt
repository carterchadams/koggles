package dev.koggles.telemetry

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.koggles.settings.orEmptyOnIoError
import dev.koggles.settings.persist
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * The analytics consent, in a store of its own under [Context.getNoBackupFilesDir] rather than in the settings file.
 * The settings file is part of Android's automatic backup; a consent kept there would come back with a restore,
 * possibly after the user had turned it off (Codex, implementation review 2026-10-14). Kept out of the backup, a
 * restored or migrated phone starts with the switch off, which is what README's Privacy section promises.
 */
class ConsentStore(private val store: DataStore<Preferences>) {
    suspend fun read(): Boolean = store.data.orEmptyOnIoError().first()[KEY] ?: false

    /** Persists [on]; false if the write failed (the gate's in-memory state is the authority either way). */
    suspend fun write(on: Boolean): Boolean = persist("consent") { store.edit { it[KEY] = on } }

    companion object {
        private val KEY = booleanPreferencesKey("consent.analytics")

        /** DataStore requires the `.preferences_pb` suffix. */
        fun file(context: Context): File = File(context.noBackupFilesDir, "datastore/consent.preferences_pb")
    }
}
