package dev.koggles.ui

import dev.koggles.audio.AudioEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuStateTest {
    private val previews = mutableListOf<Boolean>()
    private val events = mutableListOf<AudioEvent>()
    private val menu = MenuState(setPreview = { previews += it }, dispatch = { events += it })

    @Test fun open_setsPreviewAndDispatchesMenuOpened() {
        menu.toggle()
        assertTrue(menu.open.value)
        assertEquals(listOf(true), previews)
        assertEquals(listOf<AudioEvent>(AudioEvent.MenuOpened), events)
    }

    @Test fun close_restoresAndDispatchesMenuClosed() {
        menu.toggle()
        menu.close()
        assertFalse(menu.open.value)
        assertEquals(listOf(true, false), previews)
        assertEquals(listOf(AudioEvent.MenuOpened, AudioEvent.MenuClosed), events)
        menu.close()
        assertEquals("closing a closed menu is a no-op", 2, events.size)
    }

    @Test fun toggleTwice_isClosed() {
        menu.toggle(); menu.toggle()
        assertFalse(menu.open.value)
        assertEquals(listOf(AudioEvent.MenuOpened, AudioEvent.MenuClosed), events)
    }
}
