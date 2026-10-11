package dev.koggles.presets

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What was mirrored for one flattened file name. [source] is the source node id. */
@Serializable
data class MirrorEntry(val source: String, val size: Long, val lastModified: Long)

/** Keyed by flattened file name. */
@Serializable
data class MirrorManifest(val files: Map<String, MirrorEntry>)

/**
 * [copy]: (source, flattened name) pairs to copy. [delete]: flattened names to remove.
 * [collisions]: relPaths not mirrored, because an earlier relPath has the same file name or
 * because the file name is unsafe as a flat name (contains `/`, is `.`/`..`, or is empty).
 */
data class MirrorPlan(
    val copy: List<Pair<DocNode, String>>,
    val delete: List<String>,
    val collisions: List<String>,
)

/**
 * Mirrors a pack's textures, flattened by file name, into `cacheRoot/textures/<sha1(packId)>/`
 * (spec §5.2). [copy] writes a source node's bytes to a file and throws on failure. [log]
 * receives one line per collision. Not thread-safe; run off the main thread.
 */
class TextureMirror(
    private val cacheRoot: File,
    private val copy: (DocNode, File) -> Unit,
    private val log: (String) -> Unit = {},
) {

    fun dirFor(packId: String): File = File(File(cacheRoot, TEXTURES), sha1Hex(packId))

    fun plan(pack: Pack, manifest: MirrorManifest, dirExists: Boolean): MirrorPlan {
        val winners = LinkedHashMap<String, DocNode>()
        val collisions = ArrayList<String>()
        for ((relPath, node) in pack.textures.sortedBy { it.first }) {
            val name = node.name
            if (!isSafeName(name) || name in winners) collisions += relPath else winners[name] = node
        }
        val copy = winners.filter { (name, node) ->
            val e = manifest.files[name]
            !dirExists || e == null || e.source != pack.tree.sourceId(node) ||
                e.size != node.size || e.lastModified != node.lastModified
        }.map { (name, node) -> node to name }
        // A scan with read errors is incomplete: absence is not proof of deletion, so keep the cache.
        val delete = if (pack.readErrors.isNotEmpty()) emptyList()
        else manifest.files.keys.filter { it !in winners }.sorted()
        return MirrorPlan(copy, delete, collisions)
    }

    /**
     * Brings the pack's directory up to date and returns it. A failed copy leaves no final file
     * and no manifest entry for that name (a previous good copy, if any, stays); it is retried
     * on the next call. Throws [IOException] only if the directory cannot be created.
     */
    fun apply(pack: Pack): File {
        val dir = dirFor(pack.id)
        val dirExists = dir.isDirectory
        val manifestFile = File(dir, MANIFEST)
        val files = HashMap(if (dirExists) readManifest(manifestFile).files else emptyMap())
        // A manifest entry whose file was evicted individually is stale: drop it so it is re-copied.
        files.keys.retainAll { isSafeName(it) && File(dir, it).isFile }
        if (!dirExists && !dir.mkdirs()) throw IOException("cannot create $dir")
        dir.listFiles()?.forEach { if (it.name.endsWith(TMP)) it.delete() }

        val plan = plan(pack, MirrorManifest(files), dirExists)
        plan.collisions.forEach { log("texture not mirrored (name collision or unsafe name): ${pack.id}/$it") }

        if (plan.delete.isNotEmpty()) {
            for (name in plan.delete) {
                files.remove(name)
                if (isSafeName(name)) File(dir, name).delete()
            }
            writeManifest(manifestFile, files)
        }
        for ((node, name) in plan.copy) {
            val tmp = File(dir, name + TMP)
            try {
                copy(node, tmp)
                move(tmp, File(dir, name))
            } catch (e: Exception) { // IOException, or SecurityException from a revoked grant
                tmp.delete()
                log("texture copy failed: ${pack.id}/${node.name}: $e")
                continue
            }
            files[name] = MirrorEntry(pack.tree.sourceId(node), node.size, node.lastModified)
            writeManifest(manifestFile, files)
        }
        return dir
    }

    private fun readManifest(f: File): MirrorManifest = try {
        if (f.isFile) json.decodeFromString(MirrorManifest.serializer(), f.readText()) else EMPTY
    } catch (e: IOException) {
        EMPTY
    } catch (e: SerializationException) {
        EMPTY
    } catch (e: IllegalArgumentException) {
        EMPTY
    }

    private fun writeManifest(f: File, files: Map<String, MirrorEntry>) {
        val tmp = File(f.parentFile, f.name + TMP)
        try {
            tmp.writeText(json.encodeToString(MirrorManifest.serializer(), MirrorManifest(files.toSortedMap())))
            move(tmp, f)
        } catch (e: IOException) {
            // A stale manifest only causes extra copies on the next run.
            tmp.delete()
            log("texture manifest write failed: $f: $e")
        }
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val TEXTURES = "textures"
        const val MANIFEST = "manifest.json"
        private const val TMP = ".tmp"
        private val EMPTY = MirrorManifest(emptyMap())
        private val json = Json { ignoreUnknownKeys = true }

        /** A flat name must stay inside the directory and not shadow our own files. */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
                name != MANIFEST && !name.endsWith(TMP)

        fun sha1Hex(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
