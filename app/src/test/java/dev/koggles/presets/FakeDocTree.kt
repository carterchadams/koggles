package dev.koggles.presets

/**
 * In-memory [DocTree] built from `/`-separated file paths; directories are implied by the
 * paths (plus any listed in [dirs]). Node ids are the paths, the root's id is "".
 * Counts calls, and [children] throws for any directory path in [failingDirs].
 */
class FakeDocTree(
    files: Collection<String>,
    dirs: Collection<String> = emptyList(),
    private val failingDirs: Set<String> = emptySet(),
) : DocTree {

    private val fileSet = files.toSet()
    private val dirSet: Set<String> = buildSet {
        add("")
        addAll(dirs)
        for (f in fileSet) {
            var p = f.substringBeforeLast('/', "")
            while (p.isNotEmpty()) {
                add(p)
                p = p.substringBeforeLast('/', "")
            }
        }
    }

    var childrenCalls = 0
        private set
    var readTextCalls = 0
        private set

    override val root: DocNode = DocNode("", "", isDir = true, size = 0, lastModified = 0)

    override fun children(node: DocNode): List<DocNode> {
        childrenCalls++
        if (node.id in failingDirs) throw java.io.IOException("cannot list ${node.id}")
        val prefix = if (node.id.isEmpty()) "" else "${node.id}/"
        val direct = { p: String -> p.startsWith(prefix) && p.length > prefix.length && '/' !in p.substring(prefix.length) }
        val d = dirSet.filter(direct).map { DocNode(it, it.substringAfterLast('/'), true, 0, 0) }
        val f = fileSet.filter(direct).map { DocNode(it, it.substringAfterLast('/'), false, 1, 0) }
        return (d + f).sortedBy { it.name }
    }

    override fun readText(node: DocNode): String {
        readTextCalls++
        return ""
    }
}
