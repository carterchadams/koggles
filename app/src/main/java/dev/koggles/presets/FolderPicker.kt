package dev.koggles.presets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles a folder picked in the system tree picker (spec §5.1). Taking the grant is provider IPC,
 * so it runs on [io]; a failure is reported through [onFailed] (the library shows it) instead of
 * crashing, and only a successful acquisition triggers [onAcquired] (a re-index).
 */
class FolderPicker(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val acquire: suspend (String) -> Unit,
    private val onAcquired: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    fun picked(uri: String) {
        scope.launch {
            val result = try {
                withContext(io) { acquire(uri) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (result == null) onAcquired() else onFailed("Couldn't use that folder: ${result.message ?: result.javaClass.simpleName}")
        }
    }
}
