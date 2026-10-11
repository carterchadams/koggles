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
    val interstitials: Boolean,
)

sealed interface Target {
    data class Preset(val ref: PresetRef) : Target
    data object Idle : Target
}

enum class Kind { Normal, Interstitial }

data class PlayRequest(val requestId: Long, val target: Target, val kind: Kind, val notice: String? = null)

/** Notice carried by every [Target.Idle] request (Global Constraints, exact copy). */
const val EMPTY_SCOPE_NOTICE = "No playable presets in this scope"

/**
 * Rotation rules (spec §5.3, D12). Pure and single-threaded: callers serialise access.
 *
 * - Candidates: presets of enabled packs inside the scope (a folder scope includes its
 *   subfolders), minus skipped, broken and transition presets. Interstitial candidates are the
 *   transition presets of enabled packs, in any scope, minus skipped and broken ones.
 * - [current] is the preset of the most recently issued request (an interstitial included), or
 *   null after an [Target.Idle] request. It is not cleared when its pack disappears.
 * - Every issued request supersedes the previous one and cancels any pending interstitial;
 *   callbacks carrying older ids are ignored.
 */
class Playlist(private val random: Random) {

    private var inputs: PlaylistInputs? = null
    private var candidates: List<PresetRef> = emptyList()
    private var candidateKeys: Set<String> = emptySet()
    private var transitions: List<PresetRef> = emptyList()

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

    private var pendingDestination: PresetRef? = null
    private var consecutiveFailures = 0

    var current: PresetRef? = null
        private set

    var pendingInterstitial: PlayRequest? = null
        private set

    val latestRequestId: Long get() = latest?.requestId ?: 0L

    fun update(inputs: PlaylistInputs) {
        this.inputs = inputs
        pendingSkips.removeAll(inputs.skipped)
        recompute()
    }

    /** Plays [lastKey] if it is a candidate, else the next cycle pick. Never an interstitial. */
    fun start(lastKey: String?): PlayRequest {
        val ref = lastKey?.takeIf { it in candidateKeys }?.let { inputs?.catalog?.byKey(it) }
        return issueNormal(ref ?: pickFromCycle())
    }

    fun next(): PlayRequest {
        if (pendingInterstitial != null) return issueNormal(eligibleDestination())
        val destination = pickFromCycle() ?: return issueNormal(null)
        return issueWithInterstitial(destination)
    }

    /** The newest eligible history entry other than [current]; null (and no change) if none. */
    fun previous(): PlayRequest? {
        val currentKey = current?.key
        var i = minOf(cursor, history.size - 1)
        while (i >= 0) {
            val key = history[i]
            if (key != currentKey && key in candidateKeys) {
                val ref = inputs?.catalog?.byKey(key) ?: return null
                val r = issueNormal(ref)
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
        if (n <= 1) return issueNormal(candidates.firstOrNull())
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
        return issueNormal(pool[random.nextInt(pool.size)])
    }

    /** Plays any known preset, eligible or not (an explicit user choice); null if unknown. */
    fun select(key: String): PlayRequest? {
        val ref = inputs?.catalog?.byKey(key) ?: return null
        return issueNormal(ref)
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

    fun onLoaded(requestId: Long) {
        val r = latest?.takeIf { it.requestId == requestId } ?: return
        consecutiveFailures = 0
        val target = r.target as? Target.Preset ?: return
        if (r.kind != Kind.Normal) return
        if (latestHistoryIndex >= 0) {
            cursor = latestHistoryIndex
            return
        }
        history.addLast(target.ref.key)
        if (history.size > HISTORY) history.removeFirst()
        cursor = history.size - 1
    }

    fun onLoadFailed(requestId: Long): PlayRequest? {
        val r = latest?.takeIf { it.requestId == requestId } ?: return null
        val target = r.target as? Target.Preset ?: return null
        broken += target.ref.key
        recompute()
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_BOUND) return issueNormal(null)
        return if (r.kind == Kind.Interstitial) issueNormal(eligibleDestination())
        else issueNormal(pickFromCycle())
    }

    fun onInterstitialElapsed(requestId: Long): PlayRequest? {
        if (pendingInterstitial?.requestId != requestId) return null
        return issueNormal(eligibleDestination())
    }

    // ---- internals ----

    private fun recompute() {
        val inp = inputs ?: return
        val enabled = inp.catalog.packs.filter { it.id !in inp.disabledPacks }.flatMap { it.presets }
        val excluded = { ref: PresetRef -> ref.key in inp.skipped || ref.key in pendingSkips || ref.key in broken }
        val newCandidates = enabled.filter { !it.isTransition && inScope(it, inp) && !excluded(it) }
        transitions = enabled.filter { it.isTransition && !excluded(it) }
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

    private fun inScope(ref: PresetRef, inp: PlaylistInputs): Boolean = when (val s = inp.scope) {
        Scope.AllEnabled -> true
        Scope.Favorites -> ref.key in inp.favorites
        is Scope.PackOnly -> ref.packId == s.packId
        is Scope.Folder -> ref.packId == s.packId &&
            (ref.folder == s.folder || ref.folder.startsWith(s.folder + "/"))
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

    /** The pending destination if it is still a candidate, else a fresh cycle pick. */
    private fun eligibleDestination(): PresetRef? =
        pendingDestination?.takeIf { it.key in candidateKeys } ?: pickFromCycle()

    private fun issueWithInterstitial(destination: PresetRef): PlayRequest {
        if (inputs?.interstitials != true || transitions.isEmpty()) return issueNormal(destination)
        val via = transitions[random.nextInt(transitions.size)]
        val r = issue(Target.Preset(via), Kind.Interstitial)
        pendingInterstitial = r
        pendingDestination = destination
        return r
    }

    private fun issueNormal(ref: PresetRef?): PlayRequest =
        issue(if (ref == null) Target.Idle else Target.Preset(ref), Kind.Normal)

    private fun issue(target: Target, kind: Kind): PlayRequest {
        val r = PlayRequest(
            requestId = ++nextId,
            target = target,
            kind = kind,
            notice = if (target == Target.Idle) EMPTY_SCOPE_NOTICE else null,
        )
        latest = r
        latestHistoryIndex = -1
        pendingInterstitial = null
        pendingDestination = null
        current = (target as? Target.Preset)?.ref
        return r
    }

    private companion object {
        const val HISTORY = 100
        const val FAILURE_BOUND = 5
    }
}
