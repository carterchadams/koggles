package dev.koggles.playback

import dev.koggles.presets.Catalog
import dev.koggles.presets.PresetRef
import dev.koggles.settings.Scope
import kotlin.random.Random

data class PlaylistInputs(
    val catalog: Catalog,
    val disabledPacks: Set<String>,
    val favorites: Set<String>,
    val skipped: Set<String>,
    val scope: Scope,
)

sealed interface Target {
    data class Preset(val ref: PresetRef) : Target
    data object Idle : Target
}

data class PlayRequest(val requestId: Long, val target: Target, val notice: String? = null)

/** Notice carried by every [Target.Idle] request (Global Constraints, exact copy). */
const val EMPTY_SCOPE_NOTICE = "No playable presets in this scope"

/**
 * Rotation rules. Pure and single-threaded: callers serialise access.
 *
 * - Candidates: presets of enabled packs inside the scope (a folder scope includes its
 *   subfolders), minus skipped, broken and transition presets. Transition presets (a path segment
 *   starting with `!`) never play: the interstitial feature that used them was removed
 *   (the author, 2026-10-13: they showed seconds of black between presets).
 * - [current] is the preset of the most recently issued request, or null after an
 *   [Target.Idle] request. It is not cleared when its pack disappears.
 * - Every issued request supersedes the previous one; callbacks carrying older ids are ignored.
 */
class Playlist(private val random: Random, private val idFloor: () -> Long = { 0L }) {

    private var inputs: PlaylistInputs? = null
    private var candidates: List<PresetRef> = emptyList()
    private var candidateKeys: Set<String> = emptySet()

    /** Broken this session. */
    private val broken = HashSet<String>()

    /** Skipped by [skipCurrent] and not yet seen in [PlaylistInputs.skipped]. */
    private val pendingSkips = HashSet<String>()

    /** Remaining keys of the current shuffle cycle; the front is played next. */
    private val remaining = ArrayList<String>()

    /** Normal presets that reached [onLoaded], oldest first, at most [HISTORY] keys. */
    private val history = ArrayDeque<String>()

    /** Index in [history] that [previous] walks back from (inclusive), or -1. */
    private var cursor = -1

    private var nextId = 0L
    private var latest: PlayRequest? = null

    /** For the latest request: the history index it came from via [previous], else -1. */
    private var latestHistoryIndex = -1

    private var consecutiveFailures = 0

    var current: PresetRef? = null
        private set

    val latestRequestId: Long get() = latest?.requestId ?: 0L

    fun update(inputs: PlaylistInputs) {
        this.inputs = inputs
        pendingSkips.removeAll(inputs.skipped)
        recompute()
    }

    /** Plays [lastKey] if it is a candidate, else the next cycle pick. */
    fun start(lastKey: String?): PlayRequest {
        val ref = lastKey?.takeIf { it in candidateKeys }?.let { inputs?.catalog?.byKey(it) }
        return issue(ref ?: pickFromCycle())
    }

    fun next(): PlayRequest = issue(pickFromCycle())

    /** The newest eligible history entry other than [current]; null (and no change) if none. */
    fun previous(): PlayRequest? {
        val currentKey = current?.key
        var i = minOf(cursor, history.size - 1)
        while (i >= 0) {
            val key = history[i]
            if (key != currentKey && key in candidateKeys) {
                val ref = inputs?.catalog?.byKey(key) ?: return null
                val r = issue(ref)
                latestHistoryIndex = i
                cursor = i
                return r
            }
            i--
        }
        return null
    }

    fun random(): PlayRequest {
        val n = candidates.size
        if (n <= 1) return issue(candidates.firstOrNull())
        val currentKey = current?.key
        val k = minOf(10, n - 2)
        val excluded = HashSet<String>()
        currentKey?.let { excluded += it }
        val recent = LinkedHashSet<String>()
        for (i in history.indices.reversed()) {
            if (recent.size >= k) break
            val key = history[i]
            if (key != currentKey && key in candidateKeys) recent += key
        }
        excluded += recent
        val pool = candidates.filter { it.key !in excluded }
        return issue(pool[random.nextInt(pool.size)])
    }

    /** Plays any known preset, eligible or not (an explicit user choice); null if unknown. */
    fun select(key: String): PlayRequest? {
        val ref = inputs?.catalog?.byKey(key) ?: return null
        return issue(ref)
    }

    /**
     * Excludes the current preset at once (the persisted skip set arrives later through [update])
     * and advances like [next].
     */
    fun skipCurrent(): PlayRequest {
        current?.let {
            pendingSkips += it.key
            recompute()
        }
        return next()
    }

    /**
     * The latest request again under a new id above the floor: for when another loader's request
     * superseded it before it was shown and then failed, leaving an older preset on screen whose
     * switch events would be stale. Keeps its target, its notice and the history position it came
     * from; [current] is unchanged.
     */
    fun reissueLatest(): PlayRequest? {
        val r = latest ?: return null
        val again = r.copy(requestId = allocateId())
        latest = again
        return again
    }

    /**
     * Never below the renderer's current request, whoever loaded it (a debug harness, a test, a
     * preview): a lower id would be dropped as superseded and playback would freeze. Main thread only,
     * like the rest of playback; [idFloor] reads RenderCommands' state flow.
     */
    private fun allocateId(): Long {
        nextId = maxOf(nextId, idFloor()) + 1
        return nextId
    }

    fun onLoaded(requestId: Long) {
        val r = latest?.takeIf { it.requestId == requestId } ?: return
        consecutiveFailures = 0
        val target = r.target as? Target.Preset ?: return
        if (latestHistoryIndex >= 0) {
            cursor = latestHistoryIndex
            return
        }
        history.addLast(target.ref.key)
        if (history.size > HISTORY) history.removeFirst()
        cursor = history.size - 1
    }

    /**
     * The latest request's load failed: pick again. [markBroken] = false skips the preset for now
     * without excluding it for the session (it was never attempted: no capacity for its folder's
     * reads); the consecutive-failure bound still applies, so a saturated folder can't spin.
     */
    fun onLoadFailed(requestId: Long, markBroken: Boolean = true): PlayRequest? {
        val r = latest?.takeIf { it.requestId == requestId } ?: return null
        val target = r.target as? Target.Preset ?: return null
        if (markBroken) {
            broken += target.ref.key
            recompute()
        }
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_BOUND) return issue(null)
        return issue(pickFromCycle())
    }

    // ---- internals ----

    private fun recompute() {
        val inp = inputs ?: return
        val enabled = inp.catalog.packs.filter { it.id !in inp.disabledPacks }.flatMap { it.presets }
        val excluded = { ref: PresetRef -> ref.key in inp.skipped || ref.key in pendingSkips || ref.key in broken }
        val playable = enabled.filter { !it.isTransition && !excluded(it) }
        val scoped = playable.filter { inScope(it, inp) }
        // Human decision Q1: a Favorites scope with nothing playable acts as the default scope.
        val newCandidates = if (scoped.isEmpty() && inp.scope == Scope.Favorites) playable else scoped
        val oldKeys = candidateKeys
        candidates = newCandidates
        candidateKeys = newCandidates.mapTo(HashSet()) { it.key }
        remaining.retainAll(candidateKeys)
        // New candidates join a cycle in progress at random positions. An exhausted cycle picks
        // them up at its next refill anyway.
        if (remaining.isNotEmpty()) {
            for (ref in newCandidates) {
                if (ref.key !in oldKeys) remaining.add(random.nextInt(remaining.size + 1), ref.key)
            }
        }
    }

    private fun inScope(ref: PresetRef, inp: PlaylistInputs): Boolean = when (val s = effectiveScope(inp)) {
        Scope.AllEnabled -> true
        Scope.Favorites -> ref.key in inp.favorites
        is Scope.PackOnly -> ref.packId == s.packId
        is Scope.Folder -> ref.packId == s.packId &&
            (ref.folder == s.folder || ref.folder.startsWith(s.folder + "/"))
    }

    /**
     * A pack or folder scope whose pack is no longer in the catalog (the user folder was lost) plays
     * all enabled packs instead of going idle. The persisted scope is untouched, so it applies again
     * once the pack returns.
     */
    private fun effectiveScope(inp: PlaylistInputs): Scope = when (val s = inp.scope) {
        is Scope.PackOnly -> if (inp.catalog.pack(s.packId) == null) Scope.AllEnabled else s
        is Scope.Folder -> if (inp.catalog.pack(s.packId) == null) Scope.AllEnabled else s
        else -> s
    }

    /** The next key of the shuffle cycle (D12), or null when there are no candidates. */
    private fun pickFromCycle(): PresetRef? {
        val n = candidates.size
        if (n == 0) return null
        if (n == 1) return candidates[0]
        val currentKey = current?.key
        while (true) {
            if (remaining.isEmpty()) {
                candidates.mapTo(remaining) { it.key }
                remaining.shuffle(random)
                if (remaining[0] == currentKey) {
                    val j = 1 + random.nextInt(n - 1)
                    remaining[0] = remaining[j]
                    remaining[j] = currentKey
                }
            }
            val key = remaining.removeAt(0)
            // A key already showing (reached by select, say) counts as played this cycle.
            if (key == currentKey) continue
            return inputs!!.catalog.byKey(key)
        }
    }

    /** Issues a request for [ref], or an idle request (with its notice) when there is nothing to play. */
    private fun issue(ref: PresetRef?): PlayRequest {
        val target: Target = if (ref == null) Target.Idle else Target.Preset(ref)
        val r = PlayRequest(
            requestId = allocateId(),
            target = target,
            notice = if (target == Target.Idle) EMPTY_SCOPE_NOTICE else null,
        )
        latest = r
        latestHistoryIndex = -1
        current = ref
        return r
    }

    private companion object {
        const val HISTORY = 100
        const val FAILURE_BOUND = 5
    }
}
