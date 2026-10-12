package dev.koggles.presets

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * [DocTree] over a plain directory. Node ids are absolute paths. Symbolic links are skipped, so
 * traversal stays inside the root and cannot loop.
 */
class FileDocTree(private val rootDir: File) : DocTree {

    override val root: DocNode = node(rootDir)

    override fun children(node: DocNode): List<DocNode> {
        val files = File(node.id).listFiles() ?: throw IOException("cannot list ${node.id}")
        return files.filterNot { Files.isSymbolicLink(it.toPath()) }.map(::node)
    }

    override fun readText(node: DocNode): String = File(node.id).inputStream().use { it.readPresetText() }

    private fun node(f: File): DocNode {
        val isDir = f.isDirectory
        return DocNode(f.absolutePath, f.name, isDir, if (isDir) 0 else f.length(), f.lastModified())
    }
}
