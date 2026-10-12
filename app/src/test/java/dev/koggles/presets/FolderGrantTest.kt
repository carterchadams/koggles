package dev.koggles.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderGrantTest {

    @Test
    fun pickReturnsNullWhenNoGrants() {
        assertNull(GrantRules.pick(emptyList()) { true })
    }

    @Test
    fun pickSkipsUnreadable() {
        val probed = mutableListOf<String>()
        val grants = listOf(PersistedGrant("content://a", read = false), PersistedGrant("content://b", read = true))
        assertEquals("content://b", GrantRules.pick(grants) { probed += it; true })
        assertEquals(listOf("content://b"), probed)
    }

    @Test
    fun pickReturnsNullWhenProbeFails() {
        val grants = listOf(PersistedGrant("content://gone", read = true))
        assertNull(GrantRules.pick(grants) { false })
        // A later readable grant whose probe succeeds is still found.
        val two = grants + PersistedGrant("content://ok", read = true)
        assertEquals("content://ok", GrantRules.pick(two) { it == "content://ok" })
    }

    // A grant whose release failed must not shadow the folder the user chose last.
    @Test
    fun pickPrefersActiveUri_evenWhenListedSecond() {
        val grants = listOf(PersistedGrant("content://old", read = true), PersistedGrant("content://new", read = true))
        assertEquals("content://new", GrantRules.pick(grants, active = "content://new") { true })
    }

    // Once a folder was chosen, only that folder counts. A leftover grant must not
    // mask its loss (revalidation would keep a dead catalog) or resurrect an old folder.
    @Test
    fun pickActiveUnusable_returnsNull_evenWithLeftoverGrant() {
        val grants = listOf(PersistedGrant("content://old", read = true), PersistedGrant("content://new", read = true))
        assertNull(GrantRules.pick(grants, active = "content://new") { it != "content://new" })
        assertNull(GrantRules.pick(grants, active = "content://released") { true })
    }

    @Test
    fun pickWithoutActive_usesFirstReadable() {
        val grants = listOf(PersistedGrant("content://old", read = true), PersistedGrant("content://new", read = true))
        assertEquals("content://old", GrantRules.pick(grants, active = null) { true })
    }
}
