package dev.koggles.presets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Replacements run one at a time, in selection order (each enumerates and releases grants). */
    private val lock = Mutex()
    private val latest = AtomicLong(0)

    fun picked(uri: String) {
        val mine = latest.incrementAndGet()
        scope.launch {
            lock.withLock {
                // A later selection supersedes this one: it will take its own grant and refresh.
                if (mine != latest.get()) return@withLock
                val failure = try {
                    withContext(io) { acquire(uri) }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                when {
                    failure != null -> onFailed("Couldn't use that folder: ${failure.message ?: failure.javaClass.simpleName}")
                    mine == latest.get() -> onAcquired()
                }
            }
        }
    }
}
