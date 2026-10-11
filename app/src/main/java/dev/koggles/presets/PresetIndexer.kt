package dev.koggles.presets

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

sealed interface IndexProgress {
    /** All packs completed so far, in order. */
    data class Partial(val packs: List<Pack>) : IndexProgress
    data class Done(val packs: List<Pack>) : IndexProgress
}

/**
 * Walks a [DocTree] into [Pack]s. Metadata only: never calls [DocTree.readText]. Each top-level
 * directory of the tree is a pack (`id = "$idPrefix:<name>"`); root-level files are ignored.
 * A directory that fails to list is recorded in [Pack.readErrors] and the walk continues.
 */
class PresetIndexer(private val io: CoroutineDispatcher) {

    fun index(tree: DocTree, idPrefix: String): Flow<IndexProgress> = flow {
        val tops = withContext(io) { tree.children(tree.root) }.filter { it.isDir }.sortedBy { it.name }
        val done = ArrayList<Pack>(tops.size)
        for (dir in tops) {
            done += withContext(io) { walkPack(tree, dir, "$idPrefix:${dir.name}") }
            emit(IndexProgress.Partial(done.toList()))
        }
        emit(IndexProgress.Done(done.toList()))
    }

    /** Iterative DFS; checks for cancellation before listing each directory. */
    private suspend fun walkPack(tree: DocTree, packDir: DocNode, packId: String): Pack {
        val presets = ArrayList<PresetRef>()
        val textures = ArrayList<Pair<String, DocNode>>()
        val errors = ArrayList<String>()
        // (directory, its path relative to the pack root, "" for the root)
        val stack = ArrayDeque<Pair<DocNode, String>>()
        stack.addLast(packDir to "")
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, dirRel) = stack.removeLast()
            val kids = try {
                tree.children(dir)
            } catch (e: Exception) {
                errors += "${dirRel.ifEmpty { "/" }}: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            for (kid in kids.sortedByDescending { it.name }) {
                val rel = if (dirRel.isEmpty()) kid.name else "$dirRel/${kid.name}"
                if (kid.isDir) {
                    stack.addLast(kid to rel)
                    continue
                }
                when (extensionOf(kid.name)) {
                    in PRESET_EXT -> presets += PresetRef(
                        key = "$packId/$rel",
                        packId = packId,
                        relPath = rel,
                        folder = dirRel,
                        name = kid.name.substringBeforeLast('.'),
                        isTransition = rel.split('/').any { it.startsWith('!') },
                        node = kid,
                    )
                    in TEXTURE_EXT -> textures += rel to kid
                }
            }
        }
        presets.sortBy { it.relPath }
        textures.sortBy { it.first }
        return Pack(packId, packDir.name, tree, presets, textures, errors)
    }
}
