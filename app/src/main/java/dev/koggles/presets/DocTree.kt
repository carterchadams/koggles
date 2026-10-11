package dev.koggles.presets

/** One entry of a browsable tree. [id] is opaque to callers and unique within its tree. */
data class DocNode(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

interface DocTree {
    val root: DocNode
    fun children(node: DocNode): List<DocNode>
    fun readText(node: DocNode): String
}

/** Preset file extensions, lower case. Compare with [extensionOf]. */
val PRESET_EXT = setOf("milk")

/** Texture file extensions, lower case. Compare with [extensionOf]. */
val TEXTURE_EXT = setOf("jpg", "jpeg", "png", "tga", "bmp", "dds")

/** Lower-cased extension of [name] without the dot, or "" if it has none. */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
}
