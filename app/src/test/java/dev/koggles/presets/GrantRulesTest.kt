package dev.koggles.presets

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantRulesTest {
    @Test fun takeFails_releasesNothing() {
        val released = mutableListOf<String>()
        assertThrows(SecurityException::class.java) {
            GrantRules.replace(take = { throw SecurityException("no persistable grant") }, others = listOf("old"), release = { released += it })
        }
        assertTrue(released.isEmpty())
    }

    @Test fun success_releasesOthersOnly() {
        val order = mutableListOf<String>()
        GrantRules.replace(take = { order += "take" }, others = listOf("a", "b"), release = { order += "release:$it" })
        assertEquals(listOf("take", "release:a", "release:b"), order)
    }

    @Test fun releaseFailure_ignored() {
        val released = mutableListOf<String>()
        GrantRules.replace(
            take = {},
            others = listOf("a", "b"),
            release = { if (it == "a") throw IOException("gone") else released += it },
        )
        assertEquals(listOf("b"), released)
    }

    // R2.2 gate 4: the choice must be durably recorded before anything is released.
    @Test fun recordFails_undoesNewGrant_releasesNothing_throws() {
        val events = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            GrantRules.replace(
                take = {
                    GrantRules.takeAndRecord(
                        take = { events += "take" },
                        record = { events += "record"; false },
                        untake = { events += "untake" },
                    )
                },
                others = listOf("old"),
                release = { events += "release:$it" },
            )
        }
        assertEquals(listOf("take", "record", "untake"), events)
    }

    @Test fun recordSucceeds_thenOthersReleased() {
        val events = mutableListOf<String>()
        GrantRules.replace(
            take = { GrantRules.takeAndRecord({ events += "take" }, { events += "record"; true }, { events += "untake" }) },
            others = listOf("old"),
            release = { events += "release:$it" },
        )
        assertEquals(listOf("take", "record", "release:old"), events)
    }
}
