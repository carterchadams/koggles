package dev.koggles.telemetry

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Consent lives in its own store, separate from the settings (Codex, implementation review 2026-10-14): the
 * settings file is part of Android's automatic backup, so a consent kept there could come back with a restore.
 */
class ConsentStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun store() = ConsentStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("consent.preferences_pb") })

    @Test fun defaultsToFalse() = runBlocking {
        assertEquals(false, store().read())
    }

    @Test fun roundTrips() = runBlocking {
        val s = store()
        s.write(true)
        assertEquals(true, s.read())
        s.write(false)
        assertEquals(false, s.read())
    }
}
