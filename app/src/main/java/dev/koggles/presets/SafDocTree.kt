package dev.koggles.presets

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/**
 * [DocTree] over a Storage Access Framework tree grant. Node ids are document ids within
 * [treeUri]; a node is a directory iff its MIME type is [Document.MIME_TYPE_DIR]. SAF trees
 * cannot contain symbolic links, so traversal cannot loop or escape the grant.
 */
class SafDocTree(private val resolver: ContentResolver, val treeUri: Uri) : DocTree {

    override val root: DocNode =
        DocNode(DocumentsContract.getTreeDocumentId(treeUri), "", isDir = true, size = 0, lastModified = 0)

    /** The document URI of [node], usable with [ContentResolver.openInputStream]. */
    fun uriOf(node: DocNode): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.id)

    /** The full document URI: provider- and tree-qualified (spec §5.2 manifest source). */
    override fun sourceId(node: DocNode): String = uriOf(node).toString()

    /** Opens [node]'s bytes from this tree's provider. */
    fun openStream(node: DocNode): java.io.InputStream = try {
        resolver.openInputStream(uriOf(node))
    } catch (e: RuntimeException) {
        throw IOException("cannot open ${node.id}", e)
    } ?: throw IOException("cannot open ${node.id}")

    override fun children(node: DocNode): List<DocNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.id)
        val cursor = try {
            resolver.query(uri, COLUMNS, null, null, null)
        } catch (e: RuntimeException) { // SecurityException / IllegalArgumentException on a revoked grant
            throw IOException("cannot list ${node.id}", e)
        } ?: throw IOException("cannot list ${node.id}")
        return cursor.use { c ->
            // A provider still loading, or reporting an error, returns a partial listing: treat it as a
            // read failure so the indexer records it and the texture mirror keeps its cache.
            requireCompleteListing(
                loading = c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true,
                error = c.extras?.getString(DocumentsContract.EXTRA_ERROR),
                what = node.id,
            )
            val out = ArrayList<DocNode>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                val size = if (isDir || c.isNull(3)) 0L else c.getLong(3)
                val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                out += DocNode(id, name, isDir, size, mtime)
            }
            out
        }
    }

    override fun readText(node: DocNode): String {
        val input = try {
            resolver.openInputStream(uriOf(node))
        } catch (e: RuntimeException) {
            throw IOException("cannot open ${node.id}", e)
        } ?: throw IOException("cannot open ${node.id}")
        return input.use { it.readPresetText() }
    }

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Throws if a directory listing is partial (provider still loading) or carries a provider error. */
internal fun requireCompleteListing(loading: Boolean, error: String?, what: String) {
    if (loading) throw IOException("listing of $what is still loading")
    if (error != null) throw IOException("listing of $what failed: $error")
}
