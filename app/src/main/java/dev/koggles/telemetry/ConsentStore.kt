package dev.koggles.telemetry

import android.content.Context
import java.io.File
import java.io.IOException

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
 */
class ConsentStore(private val file: File) {
    fun read(): Boolean = file.isFile

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
