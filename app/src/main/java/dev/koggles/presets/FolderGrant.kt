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
     * The folder to use: the [active] (last chosen) grant if it is still readable and its [probe]
     * succeeds, else the first readable grant that probes, else null (no grant, or revoked/deleted).
     * Preferring [active] means a leftover grant (a release that failed) never shadows the user's
     * latest choice.
     */
    fun pick(grants: List<PersistedGrant>, active: String? = null, probe: (String) -> Boolean): String? {
        grants.firstOrNull { it.uri == active && it.read }?.let { if (probe(it.uri)) return it.uri }
        return grants.firstOrNull { it.read && it.uri != active && probe(it.uri) }?.uri
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

    /** The URI the user chose last; survives restarts. */
    private val prefs = context.applicationContext.getSharedPreferences("folder_grant", Context.MODE_PRIVATE)

    /** The granted tree if a readable grant exists and its root document still answers. */
    fun currentTree(): SafDocTree? {
        val grants = resolver.persistedUriPermissions.map { PersistedGrant(it.uri.toString(), it.isReadPermission) }
        val picked = GrantRules.pick(grants, prefs.getString(KEY_ACTIVE, null), ::probe) ?: return null
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
    fun onTreePicked(uri: String) {
        val picked = Uri.parse(uri)
        GrantRules.replace(
            take = {
                resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(KEY_ACTIVE, uri).commit()
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
