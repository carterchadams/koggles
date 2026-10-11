package dev.koggles.presets

import java.io.File
import java.io.IOException

/** [DocTree] over a plain directory. Node ids are absolute paths. */
class FileDocTree(private val rootDir: File) : DocTree {

    override val root: DocNode = node(rootDir)

    override fun children(node: DocNode): List<DocNode> {
        val files = File(node.id).listFiles() ?: throw IOException("cannot list ${node.id}")
        return files.map(::node)
    }

    override fun readText(node: DocNode): String = File(node.id).readText(Charsets.UTF_8)

    private fun node(f: File): DocNode {
        val isDir = f.isDirectory
        return DocNode(f.absolutePath, f.name, isDir, if (isDir) 0 else f.length(), f.lastModified())
    }
}
