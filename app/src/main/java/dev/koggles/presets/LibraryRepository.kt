package dev.koggles.presets

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private var userGeneration = 0L

    init {
        scope.launch { startup() }
    }

    /** Re-indexes the user tree (a new grant, Rescan, or a lost grant); cancels an in-flight index. */
    fun refresh() {
        scope.launch { if (_state.value.bundledReady) startUserIndex() }
    }

    /** [packId]'s mirrored texture dir (rebuilt first if evicted), then the bundled dir; existing only. */
    suspend fun texturePathsFor(packId: String): List<String> {
        val s = _state.value
        var own = s.packTextureDirs[packId]
        if (own != null && !own.isDirectory) {
            val pack = s.catalog.pack(packId)
            own = if (pack != null) withContext(io) { runCatching { mirror.apply(pack) }.getOrNull() } else null
        }
        return listOfNotNull(own, s.bundledTextureDir).filter { it.isDirectory }.map { it.absolutePath }
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
        val generation = userLock.withLock {
            userJob?.cancel()
            ++userGeneration
        }
        val tree = userTree()
        if (tree == null) {
            userLock.withLock {
                if (generation != userGeneration) return
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
            indexer.index(tree, "user").collect { progress ->
                ensureActive()
                val packs = when (progress) {
                    is IndexProgress.Partial -> progress.packs
                    is IndexProgress.Done -> progress.packs
                }
                for (pack in packs) {
                    if (pack.id in dirs) continue
                    runCatching { mirror.apply(pack) }
                        .onSuccess { dirs[pack.id] = it }
                        .onFailure { errors += "${pack.id} textures: ${it.message}" }
                    errors += pack.readErrors.map { "${pack.id}: $it" }
                }
                ensureActive()
                userLock.withLock {
                    if (generation != userGeneration) return@withLock
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
        }
        userLock.withLock { if (generation == userGeneration) userJob = job else job.cancel() }
    }

    private suspend fun indexAll(tree: DocTree, prefix: String): Pair<List<Pack>, List<String>> {
        var packs: List<Pack> = emptyList()
        indexer.index(tree, prefix).collect { p -> if (p is IndexProgress.Done) packs = p.packs }
        return packs to packs.flatMap { pack -> pack.readErrors.map { "${pack.id}: $it" } }
    }
}
