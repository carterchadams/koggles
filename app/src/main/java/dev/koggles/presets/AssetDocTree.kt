package dev.koggles.presets

import android.content.res.AssetManager
import java.io.InputStream

/** Seam over [AssetManager]; paths are asset-relative with no leading slash. */
interface AssetSource {
    /** Names directly under [path]; empty for a file or a missing path. */
    fun list(path: String): Array<String>
    fun open(path: String): InputStream
}

class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override fun list(path: String): Array<String> = assets.list(path) ?: emptyArray()
    override fun open(path: String): InputStream = assets.open(path)
}

/**
 * Read-only tree over APK assets under [rootPath] (D1: bundled presets are never extracted).
 * Node ids are asset paths. Sizes and timestamps are unknown (0): `AssetManager` can't report
 * them without opening each file.
 */
class AssetDocTree(private val src: AssetSource, rootPath: String) : DocTree {

    private val rootId = rootPath.trim('/')

    override val root: DocNode = DocNode(rootId, rootId.substringAfterLast('/'), isDir = true, size = 0, lastModified = 0)

    /**
     * One `list` call for [node], plus one probe per entry whose extension is not a known file
     * type. `AssetManager.list` is slow, so the ~10k presets are classified by name alone.
     */
    override fun children(node: DocNode): List<DocNode> =
        src.list(node.id).map { name ->
            val id = if (node.id.isEmpty()) name else "${node.id}/$name"
            val isDir = extensionOf(name) !in KNOWN_FILE_EXT && src.list(id).isNotEmpty()
            DocNode(id, name, isDir, size = 0, lastModified = 0)
        }

    override fun readText(node: DocNode): String =
        src.open(node.id).use { it.readPresetText() }

    private companion object {
        val KNOWN_FILE_EXT = PRESET_EXT + TEXTURE_EXT + setOf("txt", "md")
    }
}
