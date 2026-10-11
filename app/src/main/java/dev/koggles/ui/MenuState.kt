package dev.koggles.ui

import dev.koggles.audio.AudioEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the menu is open. Opening forces the single full-screen preview (spec §4) and tells the
 * audio machine to defer prompts (spec §6); closing reverses both. Main thread only.
 */
class MenuState(
    private val setPreview: (Boolean) -> Unit,
    private val dispatch: (AudioEvent) -> Unit,
) {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun toggle() = if (_open.value) close() else show()

    fun show() {
        if (_open.value) return
        _open.value = true
        setPreview(true)
        dispatch(AudioEvent.MenuOpened)
    }

    fun close() {
        if (!_open.value) return
        _open.value = false
        setPreview(false)
        dispatch(AudioEvent.MenuClosed)
    }
}
