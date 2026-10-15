package dev.koggles.telemetry

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The analytics consent: a marker file of its own under [Context.getNoBackupFilesDir], not a settings key.
 *
 * Why not the settings (Codex, implementation review 2026-10-14): the settings file is part of Android's automatic
 * backup, so a consent kept there would come back with a restore, possibly after the user had turned it off. Kept out
 * of the backup, a restored or migrated phone starts with the switch off, which is what README's Privacy section promises.
 *
 * Why a marker file and not a value (Codex, round 2, 2026-10-15): a write of "off" that fails (full disk) must not leave
 * "on" behind for the next start. So each operation fails in the safe direction: opting in creates the file, and if
 * that fails the consent reads as off; opting out deletes it, which needs no disk space. The gate's in-memory state is
 * the authority for the running process either way; this is only what the next process start is seeded from.
 *
 * Why opting out is synchronous and opting in is queued (Codex, rounds 3 and 4): an opt-out that was only queued
 * would be lost if the process died before the writer ran, and the next start would read the old "on". So [set]
 * deletes the file before it returns, on the caller's thread: one unlink in private storage, in exchange for the
 * revocation being durable the moment the switch shows off. Opting in is queued off the caller's thread, since losing
 * it is the safe direction, and it is stamped with a generation so that one still pending when a later opt-out ran
 * cannot recreate the file afterwards. The lock makes the generation check and the file operation one step.
 */
class ConsentStore(private val file: File, private val scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) {
    private val writer = io.limitedParallelism(1)
    private val lock = Any()
    private var generation = 0L

    fun read(): Boolean = file.isFile

    /** Persists [on]: an opt-out before this returns, an opt-in off the caller's thread unless a later decision supersedes it. */
    fun set(on: Boolean) {
        val mine = synchronized(lock) { ++generation }
        if (!on) {
            synchronized(lock) { write(false) }
            return
        }
        scope.launch(writer) { synchronized(lock) { if (generation == mine) write(true) } }
    }

    /** Persists [on]; false if it could not be (then a later start reads it as off, whatever was asked). */
    fun write(on: Boolean): Boolean = try {
        if (on) {
            file.parentFile?.mkdirs()
            file.createNewFile() || file.isFile
        } else {
            file.delete() || !file.exists()
        }
    } catch (e: IOException) {
        runCatching { android.util.Log.w("ConsentStore", "failed to save consent=$on", e) }
        false
    } catch (e: SecurityException) {
        runCatching { android.util.Log.w("ConsentStore", "failed to save consent=$on", e) }
        false
    }

    companion object {
        fun file(context: Context): File = File(context.noBackupFilesDir, "consent/analytics")
    }
}
