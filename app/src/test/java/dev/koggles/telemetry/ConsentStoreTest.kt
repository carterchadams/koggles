package dev.koggles.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Consent is a marker file of its own under noBackupFilesDir, not a settings key (Codex, implementation reviews
 * 2026-10-14/15): the settings file is part of Android's automatic backup, and a failed write of "off" must not leave
 * "on" behind. Opting in creates the file (a failure leaves consent off); opting out deletes it (needs no disk space).
 */
class ConsentStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(): File = tmp.root.resolve("consent/analytics")

    @Test fun defaultsToFalse() {
        assertEquals(false, ConsentStore(file()).read())
    }

    @Test fun roundTrips() {
        val s = ConsentStore(file())
        assertTrue(s.write(true))
        assertEquals(true, s.read())
        assertTrue(s.write(false))
        assertEquals(false, s.read())
    }

    @Test fun optingOutLeavesNoFileBehind() {
        val f = file()
        val s = ConsentStore(f)
        s.write(true)
        s.write(false)
        assertFalse("a stale file would read as consent after a restart", f.exists())
    }

    @Test fun optInThatCannotBePersistedReadsAsOff() {
        val dir = tmp.newFolder("locked")
        assumeTrue("needs a directory the test can make read-only", dir.setWritable(false) && !dir.canWrite())
        try {
            val s = ConsentStore(dir.resolve("analytics"))
            assertFalse(s.write(true))
            assertEquals(false, s.read())
        } finally {
            dir.setWritable(true)
        }
    }

    /**
     * Codex, round 3 (2026-10-15): persistence runs off the main thread, so two quick flips must not land out of order
     * (a pending "on" recreating the file after a later "off" deleted it). [ConsentStore.set] queues onto one ordered
     * writer: the decisions are persisted in the order they were made, and the last one is what the next start reads.
     */
    @Test fun decisionsArePersistedInTheOrderTheyWereMade() = runTest {
        val f = file()
        val s = ConsentStore(f, scope = this, io = StandardTestDispatcher(testScheduler))
        s.set(true)
        s.set(false)
        s.set(true)
        s.set(false)
        testScheduler.advanceUntilIdle()
        assertEquals(false, s.read())
        assertFalse(f.exists())
    }
}
