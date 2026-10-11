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
}
