package dev.koggles.presets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Runs [block] against a fresh DataStore instance on the shared file, fully closing it after. */
    private fun <T> withLibrary(block: suspend (PresetLibrary) -> T): T = runBlocking {
        val job = SupervisorJob()
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
        ) { tmp.root.resolve("x.preferences_pb") }
        try {
            block(PresetLibrary(store))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun packsEnabledByDefault() = withLibrary { lib ->
        assertEquals(emptySet<String>(), lib.disabledPacks.first())
        assertEquals(emptySet<String>(), lib.favorites.first())
        assertEquals(emptySet<String>(), lib.skipped.first())
        lib.setPackEnabled("user:a", false)
        lib.setPackEnabled("user:b", false)
        assertEquals(setOf("user:a", "user:b"), lib.disabledPacks.first())
        lib.setPackEnabled("user:a", true)
        assertEquals(setOf("user:b"), lib.disabledPacks.first())
    }

    @Test fun skipAndFavoritePersistAcrossInstances() {
        withLibrary { lib ->
            lib.toggleFavorite("bundled:c/x.milk")
            lib.toggleFavorite("bundled:c/y.milk")
            lib.toggleFavorite("bundled:c/y.milk") // toggled back off
            lib.setSkipped("user:p/z.milk", true)
            lib.setPackEnabled("user:p", false)
        }
        withLibrary { lib ->
            assertEquals(setOf("bundled:c/x.milk"), lib.favorites.first())
            assertEquals(setOf("user:p/z.milk"), lib.skipped.first())
            assertEquals(setOf("user:p"), lib.disabledPacks.first())
            lib.setSkipped("user:p/z.milk", false)
            assertEquals(emptySet<String>(), lib.skipped.first())
        }
    }

    @Test fun dormantKeysRetained() {
        // Keys for presets whose folder has vanished are never pruned; they come back if it reappears.
        withLibrary { lib ->
            lib.toggleFavorite("user:gone/a.milk")
            lib.setSkipped("user:gone/b.milk", true)
        }
        withLibrary { lib ->
            lib.toggleFavorite("user:here/c.milk")
            lib.setSkipped("user:here/d.milk", true)
            lib.setPackEnabled("user:here", false)
        }
        withLibrary { lib ->
            assertEquals(setOf("user:gone/a.milk", "user:here/c.milk"), lib.favorites.first())
            assertEquals(setOf("user:gone/b.milk", "user:here/d.milk"), lib.skipped.first())
        }
    }
}
