package dev.koggles.audio

import android.content.Intent

/** A device-audio source that pushes PCM into the native engine while started. */
interface AudioSource {
    fun start(listener: AudioSourceListener)
    fun stop()
}

/** Source callbacks; may arrive on any thread. */
interface AudioSourceListener {
    fun onStarted()
    fun onFailed(reason: FailReason)
    fun onProjectionStopped()
}

/** The MediaProjection consent result handed to the High-quality source. */
data class ConsentData(val resultCode: Int, val data: Intent)

/** Creates the source for [source]; [generation] identifies this start so late callbacks from an older start can be dropped. */
fun interface SourceFactory {
    fun create(source: Source, consent: ConsentData?, generation: Long): AudioSource
}

fun interface ExplainerStore {
    suspend fun markSeen()
}
