package dev.koggles.presets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** A persisted URI permission, reduced to what [GrantRules] needs. */
data class PersistedGrant(val uri: String, val read: Boolean)

object GrantRules {
    /** The first readable grant whose [probe] succeeds, else null (no grant, or revoked/deleted). */
    fun pick(grants: List<PersistedGrant>, probe: (String) -> Boolean): String? =
        grants.firstOrNull { it.read && probe(it.uri) }?.uri
}

/** The single user folder grant (spec §5.1, user packs). */
class FolderGrant(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /** The granted tree if a readable grant exists and its root document still answers. */
    fun currentTree(): SafDocTree? {
        val grants = resolver.persistedUriPermissions.map { PersistedGrant(it.uri.toString(), it.isReadPermission) }
        val picked = GrantRules.pick(grants, ::probe) ?: return null
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

    /** Persists read access to [uri] and releases every other persisted grant. */
    fun onTreePicked(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (p in resolver.persistedUriPermissions) {
            if (p.uri == uri) continue
            var flags = 0
            if (p.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (p.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                resolver.releasePersistableUriPermission(p.uri, flags)
            } catch (e: SecurityException) {
                // Already gone; nothing to release.
            }
        }
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
}
