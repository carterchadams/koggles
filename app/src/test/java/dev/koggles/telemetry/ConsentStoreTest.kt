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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Consent is a marker file of its own under noBackupFilesDir, not a settings key (Codex, implementation reviews
 * 2026-10-14/15): the settings file is part of Android's automatic backup, and a failed write of "off" must not leave
 * "on" behind. Opting in creates the file (a failure leaves consent off); opting out deletes it (needs no disk space).
 */
class ConsentStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun file(): File = tmp.root.resolve("consent/analytics")

    @Test fun defaultsToFalse() {
        assertEquals(false, ConsentStore(file(), scope).read())
    }

    @Test fun roundTrips() {
        val s = ConsentStore(file(), scope)
        assertTrue(s.write(true))
        assertEquals(true, s.read())
        assertTrue(s.write(false))
        assertEquals(false, s.read())
    }

    @Test fun optingOutLeavesNoFileBehind() {
        val f = file()
        val s = ConsentStore(f, scope)
        s.write(true)
        s.write(false)
        assertFalse("a stale file would read as consent after a restart", f.exists())
    }

    @Test fun optInThatCannotBePersistedReadsAsOff() {
        val dir = tmp.newFolder("locked")
        assumeTrue("needs a directory the test can make read-only", dir.setWritable(false) && !dir.canWrite())
        try {
            val s = ConsentStore(dir.resolve("analytics"), scope)
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

    /**
     * Codex, round 4 (2026-10-15): an opt-out that is only queued is lost if the process dies before the writer runs,
     * and the next start would read the old "on". So opting out deletes the file before [ConsentStore.set] returns,
     * on the caller's thread (one unlink in private storage); only opting in is queued, since losing that is safe.
     */
    @Test fun optOutIsDurableBeforeSetReturns_evenWithTheWriterWithheld() = runTest {
        val f = file()
        val withheld = StandardTestDispatcher(testScheduler)
        val s = ConsentStore(f, scope = this, io = withheld)
        assertTrue(s.write(true))
        s.set(false)
        // No advanceUntilIdle: the writer never runs, as if the process died right here.
        assertEquals(false, ConsentStore(f, scope = this, io = withheld).read())
        assertFalse(f.exists())
    }

    /** A queued opt-in made before an opt-out must not recreate the file when it finally runs. */
    @Test fun aStaleQueuedOptInCannotResurrectConsent() = runTest {
        val f = file()
        val s = ConsentStore(f, scope = this, io = StandardTestDispatcher(testScheduler))
        s.set(true) // queued, not yet run
        s.set(false) // durable now
        testScheduler.advanceUntilIdle() // the stale opt-in runs
        assertEquals(false, s.read())
        assertFalse(f.exists())
    }

    /** An opt-in made after an opt-out still lands. */
    @Test fun anOptInAfterAnOptOutStillLands() = runTest {
        val s = ConsentStore(file(), scope = this, io = StandardTestDispatcher(testScheduler))
        s.set(false)
        s.set(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, s.read())
    }
}
