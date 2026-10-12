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
 * Handles a folder picked in the system tree picker. Taking the grant is provider IPC,
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

    /** A grant was replaced but the catalog not yet refreshed (guarded by [lock]). */
    private var refreshOwed = false

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
                if (failure == null) {
                    refreshOwed = true // the persisted grant changed; the catalog must follow it
                } else {
                    onFailed("Couldn't use that folder: ${failure.message ?: failure.javaClass.simpleName}")
                }
                // The newest selection settles the catalog: one refresh for every grant change
                // that happened, even when this selection itself failed.
                if (mine == latest.get() && refreshOwed) {
                    refreshOwed = false
                    onAcquired()
                }
            }
        }
    }
}
