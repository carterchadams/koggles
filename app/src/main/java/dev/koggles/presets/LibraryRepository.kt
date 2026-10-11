package dev.koggles.presets

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LibraryState(
    val catalog: Catalog,
    val bundledReady: Boolean,
    val userIndexing: Boolean,
    val hasGrant: Boolean,
    val bundledTextureDir: File?,
    val packTextureDirs: Map<String, File>,
    val errors: List<String>,
)

/**
 * Owns the preset catalog: bundled presets (from assets) plus user packs (from the folder grant),
 * the bundled texture install, and per-pack texture mirrors.
 *
 * Readiness (F4): [LibraryState.bundledReady] turns true only once bundled indexing is done AND the
 * texture install has settled (an existing directory, or a recorded failure).
 */
class LibraryRepository(
    private val scope: CoroutineScope,
    private val bundledTree: () -> DocTree,
    private val installTextures: () -> File,
    private val userTree: () -> DocTree?,
    private val mirror: TextureMirror,
    private val indexer: PresetIndexer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(
        LibraryState(Catalog(emptyList()), false, false, false, null, emptyMap(), emptyList()),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var bundledPacks: List<Pack> = emptyList()
    private var bundledErrors: List<String> = emptyList()
    private var userJob: Job? = null
    private val userLock = Mutex()

    /** TextureMirror is not thread-safe: every apply (indexing and lazy rebuilds) runs under this. */
    private val mirrorLock = Mutex()
    @Volatile private var userGeneration = 0L

    /** Generation of the user index whose results are in [state] (0 = none yet). */
    @Volatile private var publishedGeneration = 0L

    init {
        scope.launch { startup() }
    }

    /** Re-indexes the user tree (a new grant, Rescan, or a lost grant); cancels an in-flight index. */
    fun refresh() {
        scope.launch { if (_state.value.bundledReady) startUserIndex() }
    }

    /**
     * [packId]'s mirrored texture dir, then the bundled dir (existing only). If any of a user pack's
     * textures is missing — evicted directory or file, or an earlier failed copy — the mirror is
     * re-applied first (spec §5.2), unless a newer index has superseded this state.
     */
    suspend fun texturePathsFor(packId: String): List<String> {
        val generation = publishedGeneration
        val s = _state.value
        var own = s.packTextureDirs[packId]
        val pack = s.catalog.pack(packId)
        if (pack != null && packId.startsWith("user:") && mirrorIncomplete(pack, own)) {
            own = withContext(io) {
                mirrorLock.withLock {
                    // A refresh started since this state was read: the pack may be stale, and the
                    // new index owns the mirror now. Never apply a superseded pack.
                    if (generation != userGeneration) own
                    else runCatching { mirror.apply(pack) }.getOrDefault(own)
                }
            }
        }
        return listOfNotNull(own, s.bundledTextureDir).filter { it.isDirectory }.map { it.absolutePath }
    }

    private fun mirrorIncomplete(pack: Pack, dir: File?): Boolean {
        if (pack.textures.isEmpty()) return false
        if (dir == null || !dir.isDirectory) return true
        return pack.textures.any { (_, node) -> TextureMirror.isSafeName(node.name) && !File(dir, node.name).isFile }
    }

    private suspend fun startup() = coroutineScope {
        val install = async(io) { runCatching { installTextures() } }
        val packs = async(io) { indexAll(bundledTree(), "bundled") }
        val (bundled, indexErrors) = packs.await()
        val installed = install.await()
        val textureDir = installed.getOrNull()?.takeIf { it.isDirectory }
        val installError = when {
            installed.isFailure -> "bundled textures: ${installed.exceptionOrNull()?.message}"
            textureDir == null -> "bundled textures unavailable"
            else -> null
        }
        bundledPacks = bundled
        bundledErrors = indexErrors + listOfNotNull(installError)
        _state.update {
            it.copy(
                catalog = Catalog(bundled),
                bundledReady = true,
                bundledTextureDir = textureDir,
                errors = bundledErrors,
            )
        }
        startUserIndex()
    }

    private suspend fun startUserIndex() {
        val (generation, previous) = userLock.withLock { ++userGeneration to userJob }
        // Wait for the superseded index to stop: its blocking mirror copies must not overlap ours.
        previous?.cancelAndJoin()
        // FolderGrant probes the provider (IPC): never on the caller's (main) thread.
        val tree = withContext(io) { userTree() }
        if (generation != userGeneration) return
        if (tree == null) {
            userLock.withLock {
                if (generation != userGeneration) return
                publishedGeneration = generation
                _state.update {
                    it.copy(
                        catalog = Catalog(bundledPacks),
                        userIndexing = false,
                        hasGrant = false,
                        packTextureDirs = emptyMap(),
                        errors = bundledErrors,
                    )
                }
            }
            return
        }
        _state.update { it.copy(hasGrant = true, userIndexing = true) }
        val job = scope.launch(io) {
            val dirs = HashMap<String, File>()
            val errors = ArrayList<String>()
            try {
            indexer.index(tree, "user").collect { progress ->
                ensureActive()
                val packs = when (progress) {
                    is IndexProgress.Partial -> progress.packs
                    is IndexProgress.Done -> progress.packs
                }
                for (pack in packs) {
                    if (pack.id in dirs) continue
                    mirrorLock.withLock { runCatching { mirror.apply(pack) } }
                        .onSuccess { dirs[pack.id] = it }
                        .onFailure { errors += "${pack.id} textures: ${it.message}" }
                    errors += pack.readErrors.map { "${pack.id}: $it" }
                }
                ensureActive()
                userLock.withLock {
                    if (generation != userGeneration) return@withLock
                    publishedGeneration = generation
                    _state.update {
                        it.copy(
                            catalog = Catalog(bundledPacks + packs),
                            userIndexing = progress !is IndexProgress.Done,
                            hasGrant = true,
                            packTextureDirs = dirs.toMap(),
                            errors = bundledErrors + errors,
                        )
                    }
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The folder is gone, unreadable or revoked (spec §7): hide user packs, keep bundled.
                val stillGranted = runCatching { userTree() != null }.getOrDefault(false)
                userLock.withLock {
                    if (generation == userGeneration) {
                        publishedGeneration = generation
                        _state.update {
                            it.copy(
                                catalog = Catalog(bundledPacks),
                                userIndexing = false,
                                hasGrant = stillGranted,
                                packTextureDirs = emptyMap(),
                                errors = bundledErrors + errors + "user folder: ${e.message}",
                            )
                        }
                    }
                }
            }
        }
        userLock.withLock { if (generation == userGeneration) userJob = job else job.cancel() }
    }

    private suspend fun indexAll(tree: DocTree, prefix: String): Pair<List<Pack>, List<String>> {
        var packs: List<Pack> = emptyList()
        indexer.index(tree, prefix).collect { p -> if (p is IndexProgress.Done) packs = p.packs }
        return packs to packs.flatMap { pack -> pack.readErrors.map { "${pack.id}: $it" } }
    }
}
