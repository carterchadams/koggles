package dev.koggles.presets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** A persisted URI permission, reduced to what [GrantRules] needs. */
data class PersistedGrant(val uri: String, val read: Boolean)

object GrantRules {
    /**
     * The folder to use. Once the user has chosen one ([active] is set), only that folder counts:
     * it is returned if still readable and its [probe] succeeds, else null, so its loss is seen as
     * a loss even when a leftover grant (a release that failed) remains. Without an [active]
     * record (grants from before it was kept), the first readable grant that probes.
     */
    fun pick(grants: List<PersistedGrant>, active: String? = null, probe: (String) -> Boolean): String? {
        if (active != null) {
            return grants.firstOrNull { it.uri == active && it.read }?.uri?.takeIf(probe)
        }
        return grants.firstOrNull { it.read && probe(it.uri) }?.uri
    }

    /**
     * Takes a new grant and durably [record]s it as the active folder. If recording fails, the
     * previous record is put back ([restore]; a failed `commit()` may already have changed it in
     * memory), the new grant is undone ([untake], best effort), and [IOException] is thrown, so the
     * caller releases nothing and the previous folder stays the active one.
     */
    fun takeAndRecord(take: () -> Unit, record: () -> Boolean, restore: () -> Unit, untake: () -> Unit) {
        take()
        if (!record()) {
            restore()
            try {
                untake()
            } catch (e: Exception) {
                // Best effort: a stray extra grant is harmless, [pick] only honours the active one.
            }
            throw java.io.IOException("couldn't save the folder choice")
        }
    }

    /**
     * Grant replacement: [take] the new grant first. If that throws, nothing is released and the
     * error propagates, so the previous folder stays usable. Then release [others]; a release that
     * fails (already gone) is ignored.
     */
    fun <G> replace(take: () -> Unit, others: List<G>, release: (G) -> Unit) {
        take()
        for (g in others) {
            try {
                release(g)
            } catch (e: Exception) {
                // Already released or revoked; nothing to undo.
            }
        }
    }
}

/** The single user folder grant (spec §5.1, user packs). */
class FolderGrant(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /** Guards the active record across a whole replacement (take, record, rollback, release). */
    private val lock = Any()

    /** The URI the user chose last; survives restarts. */
    private val prefs = context.applicationContext.getSharedPreferences("folder_grant", Context.MODE_PRIVATE)

    /** The granted tree if a readable grant exists and its root document still answers. */
    fun currentTree(): SafDocTree? {
        val grants = resolver.persistedUriPermissions.map { PersistedGrant(it.uri.toString(), it.isReadPermission) }
        // Never observes a replacement half-way (e.g. a rejected record before its rollback).
        val active = synchronized(lock) { prefs.getString(KEY_ACTIVE, null) }
        val picked = GrantRules.pick(grants, active, ::probe) ?: return null
        return SafDocTree(resolver, Uri.parse(picked))
    }

    /** `ACTION_OPEN_DOCUMENT_TREE`, hinting the primary storage root as the initial location. */
    fun openTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        .putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            // Spec §5.1: open the picker at Documents (a hint only).
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"),
        )

    /**
     * Persists read access to [uri] and releases every other persisted grant. Provider IPC: call
     * off the main thread. Throws (e.g. [SecurityException]) when the grant cannot be taken, leaving
     * existing grants untouched.
     */
    fun onTreePicked(uri: String) = synchronized(lock) {
        val picked = Uri.parse(uri)
        GrantRules.replace(
            take = {
                val alreadyHeld = resolver.persistedUriPermissions.any { it.uri == picked && it.isReadPermission }
                val previousActive = prefs.getString(KEY_ACTIVE, null)
                GrantRules.takeAndRecord(
                    take = { resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                    record = { prefs.edit().putString(KEY_ACTIVE, uri).commit() },
                    restore = {
                        val e = prefs.edit()
                        if (previousActive == null) e.remove(KEY_ACTIVE) else e.putString(KEY_ACTIVE, previousActive)
                        e.commit()
                    },
                    untake = {
                        if (!alreadyHeld) resolver.releasePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            },
            others = resolver.persistedUriPermissions.filter { it.uri != picked },
            release = { p ->
                var flags = 0
                if (p.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (p.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                resolver.releasePersistableUriPermission(p.uri, flags)
            },
        )
    }

    private fun probe(uri: String): Boolean = try {
        val tree = Uri.parse(uri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(doc, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0) == Document.MIME_TYPE_DIR
        } ?: false
    } catch (e: Exception) { // SecurityException on revoke, IllegalArgumentException/FileNotFound on delete
        false
    }

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}
