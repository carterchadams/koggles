package dev.koggles.presets

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantSnapshotTest {

    /**
     * A folder replacement swaps the grants and the active record under the lock. A snapshot taken
     * while a replacement is waiting must be all-old or all-new, never old grants with the new
     * active URI (which GrantRules.pick would read as a lost folder).
     */
    @Test fun snapshot_isConsistentAcrossAReplacement() {
        val lock = Any()
        val grants = AtomicReference(listOf(PersistedGrant("content://old", read = true)))
        val active = AtomicReference<String?>("content://old")
        val grantsRead = CountDownLatch(1)
        val replacementDone = CountDownLatch(1)
        val snap = GrantSnapshot(
            lock,
            grants = {
                val g = grants.get()
                grantsRead.countDown()
                // Give a replacement the chance to run between the two reads.
                replacementDone.await(300, TimeUnit.MILLISECONDS)
                g
            },
            active = { active.get() },
        )
        val replacement = Thread {
            grantsRead.await()
            synchronized(lock) {
                grants.set(listOf(PersistedGrant("content://new", read = true)))
                active.set("content://new")
            }
            replacementDone.countDown()
        }
        replacement.start()
        val (g, a) = snap.snapshot()
        replacement.join()
        assertEquals("grants and active record must come from the same state", a, g.single().uri)
    }
}
