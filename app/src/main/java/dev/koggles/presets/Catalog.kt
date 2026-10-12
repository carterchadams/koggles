package dev.koggles.presets

/**
 * One `.milk` file inside a pack. [key] is `"$packId/$relPath"`, stable across re-indexing so marks
 * and the last-played key survive restarts; [relPath] uses `/`
 * separators. [folder] is the parent of [relPath] (`""` at the pack root) and [name] the file
 * name without its extension. [isTransition]: some segment of [relPath] starts with `!`.
 */
data class PresetRef(
    val key: String,
    val packId: String,
    val relPath: String,
    val folder: String,
    val name: String,
    val isTransition: Boolean,
    val node: DocNode,
)

/**
 * A top-level folder of a source tree. [textures] are (relPath, node) pairs for every image file
 * anywhere in the pack; [readErrors] describe directories that could not be listed.
 */
data class Pack(
    val id: String,
    val name: String,
    val tree: DocTree,
    val presets: List<PresetRef>,
    val textures: List<Pair<String, DocNode>>,
    val readErrors: List<String>,
)

data class Catalog(val packs: List<Pack>) {
    private val byKeyMap: Map<String, PresetRef> by lazy {
        HashMap<String, PresetRef>().also { m -> packs.forEach { p -> p.presets.forEach { m[it.key] = it } } }
    }
    private val byPackId: Map<String, Pack> by lazy { packs.associateBy { it.id } }

    fun byKey(key: String): PresetRef? = byKeyMap[key]
    fun pack(id: String): Pack? = byPackId[id]
}
