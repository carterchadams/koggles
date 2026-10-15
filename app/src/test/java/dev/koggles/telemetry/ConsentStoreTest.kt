package dev.koggles.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
}
