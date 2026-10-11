package dev.koggles.playback

import dev.koggles.presets.Catalog
import dev.koggles.presets.DocNode
import dev.koggles.presets.FakeDocTree
import dev.koggles.presets.Pack
import dev.koggles.presets.PresetRef
import dev.koggles.settings.Scope

/** A [PresetRef] for `"$packId/$relPath"`, derived the way the indexer derives it. */
fun presetRef(packId: String, relPath: String): PresetRef {
    val folder = relPath.substringBeforeLast('/', "")
    val file = relPath.substringAfterLast('/')
    return PresetRef(
        key = "$packId/$relPath",
        packId = packId,
        relPath = relPath,
        folder = folder,
        name = file.substringBeforeLast('.'),
        isTransition = relPath.split('/').any { it.startsWith("!") },
        node = DocNode(relPath, file, isDir = false, size = 1, lastModified = 0),
    )
}

/** A pack holding [relPaths] (in that order), backed by a [FakeDocTree]. */
fun pack(id: String, vararg relPaths: String): Pack = Pack(
    id = id,
    name = id.substringAfter(':'),
    tree = FakeDocTree(relPaths.toList()),
    presets = relPaths.map { presetRef(id, it) },
    textures = emptyList(),
    readErrors = emptyList(),
)

fun catalogOf(vararg packs: Pack): Catalog = Catalog(packs.toList())

/** One bundled pack `bundled:p` with [n] root presets `p0.milk .. p{n-1}.milk`. */
fun flatCatalog(n: Int, packId: String = "bundled:p"): Catalog =
    catalogOf(pack(packId, *Array(n) { "p$it.milk" }))

fun keysOf(catalog: Catalog, packId: String = "bundled:p"): List<String> =
    catalog.pack(packId)!!.presets.map { it.key }

fun inputs(
    catalog: Catalog,
    disabledPacks: Set<String> = emptySet(),
    favorites: Set<String> = emptySet(),
    skipped: Set<String> = emptySet(),
    scope: Scope = Scope.AllEnabled,
    interstitials: Boolean = false,
) = PlaylistInputs(catalog, disabledPacks, favorites, skipped, scope, interstitials)
