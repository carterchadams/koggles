package dev.koggles.playback

import kotlinx.coroutines.flow.StateFlow

/** What the UI drives playback through; [PlaybackController] in the app, a fake in UI tests. */
interface PlaybackControls {
    val nowPlaying: StateFlow<NowPlaying>
    fun next()
    fun previous()
    fun random()
    fun select(key: String)
    suspend fun skip()
    suspend fun toggleFavorite()
    suspend fun setLocked(on: Boolean)
}
