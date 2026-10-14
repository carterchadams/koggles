package dev.koggles.playback

import dev.koggles.presets.Catalog
import dev.koggles.settings.Scope
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {

    private val notice = "No playable presets in this scope"

    private fun playlist(catalog: Catalog, seed: Int = 42, configure: (PlaylistInputs) -> PlaylistInputs = { it }) =
        Playlist(Random(seed)).also { it.update(configure(inputs(catalog))) }

    private fun keyOf(r: PlayRequest): String? = (r.target as? Target.Preset)?.ref?.key

    private fun presetKey(r: PlayRequest): String {
        assertTrue("expected a preset, got $r", r.target is Target.Preset)
        return keyOf(r)!!
    }

    private fun assertIdle(r: PlayRequest) {
        assertEquals(Target.Idle, r.target)
        assertEquals(notice, r.notice)
    }

    /** Issues [op], reports it loaded, and returns its key. */
    private fun Playlist.play(op: Playlist.() -> PlayRequest): String {
        val r = op()
        onLoaded(r.requestId)
        return presetKey(r)
    }

    /** ▶ next, reported loaded. */
    private fun Playlist.advance(): PlayRequest {
        val r = next()
        onLoaded(r.requestId)
        return r
    }

    /** Every key ▶ next reaches over [calls] calls. */
    private fun Playlist.reachable(calls: Int = 60): Set<String> =
        (1..calls).mapNotNull { keyOf(advance()) }.toSet()

    // ---- Candidates and scope ----

    @Test fun candidatesExcludeDisabledSkippedBrokenTransition() {
        val cat = catalogOf(
            pack("bundled:a", "x.milk", "sub/y.milk", "!trans/t.milk", "deep/!t2/u.milk", "z.milk", "w.milk"),
            pack("user:b", "b1.milk", "b2.milk"),
        )
        val p = playlist(cat) { it.copy(disabledPacks = setOf("user:b"), skipped = setOf("bundled:a/z.milk")) }
        val broken = p.select("bundled:a/x.milk")!!
        assertNotNull(p.onLoadFailed(broken.requestId))
        assertEquals(setOf("bundled:a/sub/y.milk", "bundled:a/w.milk"), p.reachable())
        val randoms = (1..40).map { p.play { random() } }.toSet()
        assertEquals(setOf("bundled:a/sub/y.milk", "bundled:a/w.milk"), randoms)
    }

    @Test fun scopeFolderIncludesSubfolders() {
        val cat = catalogOf(pack("bundled:a", "f/a.milk", "f/sub/b.milk", "f/sub/deeper/c.milk", "fx/d.milk", "g/e.milk", "r.milk"))
        val p = playlist(cat) { it.copy(scope = Scope.Folder("bundled:a", "f")) }
        assertEquals(setOf("bundled:a/f/a.milk", "bundled:a/f/sub/b.milk", "bundled:a/f/sub/deeper/c.milk"), p.reachable())
        p.update(inputs(cat, scope = Scope.Folder("bundled:a", "f/sub")))
        assertEquals(setOf("bundled:a/f/sub/b.milk", "bundled:a/f/sub/deeper/c.milk"), p.reachable())
    }

    @Test fun scopePackOnly() {
        val cat = catalogOf(pack("bundled:a", "a0.milk", "a1.milk"), pack("user:b", "b0.milk", "s/b1.milk"))
        val p = playlist(cat) { it.copy(scope = Scope.PackOnly("user:b")) }
        assertEquals(setOf("user:b/b0.milk", "user:b/s/b1.milk"), p.reachable())
    }

    @Test fun scopeFavoritesOnly() {
        val cat = catalogOf(pack("bundled:a", "a0.milk", "a1.milk", "a2.milk"), pack("user:b", "b0.milk", "b1.milk"), pack("user:c", "c0.milk"))
        val favs = setOf("bundled:a/a1.milk", "user:b/b0.milk", "user:c/c0.milk", "user:gone/x.milk")
        val p = playlist(cat) { it.copy(scope = Scope.Favorites, favorites = favs, disabledPacks = setOf("user:c")) }
        assertEquals(setOf("bundled:a/a1.milk", "user:b/b0.milk"), p.reachable())
    }

    // A scope naming a pack that disappeared (grant lost) falls back to bundled playback instead of idling.
    @Test fun packScope_packGone_playsAllEnabled() {
        val cat = catalogOf(pack("bundled:a", "a0.milk", "a1.milk"))
        val p = playlist(cat) { it.copy(scope = Scope.PackOnly("user:b")) }
        assertEquals(setOf("bundled:a/a0.milk", "bundled:a/a1.milk"), p.reachable())
    }

    @Test fun folderScope_packGone_playsAllEnabled() {
        val cat = catalogOf(pack("bundled:a", "a0.milk", "a1.milk"))
        val p = playlist(cat) { it.copy(scope = Scope.Folder("user:b", "f")) }
        assertEquals(setOf("bundled:a/a0.milk", "bundled:a/a1.milk"), p.reachable())
    }

    @Test fun packScope_packPresentButEmpty_stillIdleWithNotice() {
        val cat = catalogOf(pack("bundled:a", "a0.milk"), pack("user:b", "!t/only-transition.milk"))
        val p = playlist(cat) { it.copy(scope = Scope.PackOnly("user:b")) }
        assertIdle(p.next())
    }

    // Human decision Q1 (2026-10-11): "empty favorites scope should just act as default behavior".
    @Test fun favoritesScope_noFavorites_playsAllEnabled() {
        val cat = flatCatalog(3)
        val p = playlist(cat) { it.copy(scope = Scope.Favorites) }
        assertEquals(keysOf(cat).toSet(), p.reachable())
    }

    @Test fun favoritesScope_onlyMissingFavorites_playsAllEnabled() {
        val cat = flatCatalog(3)
        val p = playlist(cat) { it.copy(scope = Scope.Favorites, favorites = setOf("user:gone/x.milk")) }
        assertEquals(keysOf(cat).toSet(), p.reachable())
    }

    @Test fun favoritesScope_allFavoritesSkipped_playsAllEnabled() {
        val cat = flatCatalog(3)
        val p = playlist(cat) {
            it.copy(scope = Scope.Favorites, favorites = setOf("bundled:p/p0.milk"), skipped = setOf("bundled:p/p0.milk"))
        }
        assertEquals(setOf("bundled:p/p1.milk", "bundled:p/p2.milk"), p.reachable())
    }

    @Test fun favoritesScope_gainsAFavorite_restrictsAgain() {
        val cat = flatCatalog(3)
        val p = playlist(cat) { it.copy(scope = Scope.Favorites) }
        assertEquals(3, p.reachable().size)
        p.update(inputs(cat, scope = Scope.Favorites, favorites = setOf("bundled:p/p1.milk")))
        assertEquals(setOf("bundled:p/p1.milk"), p.reachable())
    }

    @Test fun scopeFolderOfDisabledPack_isEmpty() {
        val cat = catalogOf(pack("bundled:a", "a0.milk"), pack("user:b", "f/b0.milk", "f/b1.milk"))
        val p = playlist(cat) { it.copy(scope = Scope.Folder("user:b", "f"), disabledPacks = setOf("user:b")) }
        assertIdle(p.next())
        assertIdle(p.random())
    }

    @Test fun emptyScope_idleWithNotice() {
        val cat = flatCatalog(3)
        // A folder scope whose pack exists but holds nothing in that folder.
        val p = playlist(cat) { it.copy(scope = Scope.Folder("bundled:p", "nope")) }
        assertIdle(p.start(null))
        assertIdle(p.next())
        assertIdle(p.random())
        assertIdle(p.skipCurrent())
        assertNull(p.current)
        assertNull(p.previous())
    }

    // ---- Random ----

    @Test fun singleCandidate_nextAndRandomReturnIt() {
        val p = playlist(flatCatalog(1))
        repeat(5) {
            assertEquals("bundled:p/p0.milk", p.play { next() })
            assertEquals("bundled:p/p0.milk", p.play { random() })
        }
    }

    /** Asserts the avoidance rule over [calls] random() calls on an [n]-preset scope. */
    private fun checkRandomAvoidance(n: Int, calls: Int) {
        val p = playlist(flatCatalog(n))
        val loaded = ArrayList<String>()
        val k = minOf(10, n - 2)
        repeat(calls) {
            val current = p.current?.key
            val excluded = buildSet {
                current?.let { add(it) }
                addAll(loaded.asReversed().filter { it != current }.distinct().take(k))
            }
            val r = p.random()
            val key = presetKey(r)
            assertFalse("n=$n picked $key, excluded $excluded", key in excluded)
            p.onLoaded(r.requestId)
            loaded += key
        }
    }

    @Test fun random_twelve_avoidsCurrentAndLastTen() = checkRandomAvoidance(12, 200)

    @Test fun random_smallScopes_neverIdle_neverCurrent() {
        for (n in 2..11) checkRandomAvoidance(n, 50)
    }

    @Test fun random_twoCandidates_alternates() {
        val p = playlist(flatCatalog(2))
        val seq = (1..20).map { p.play { random() } }
        seq.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun random_afterScopeShrinks_stillPicks() {
        val cat = flatCatalog(12)
        val keys = keysOf(cat)
        val p = playlist(cat)
        repeat(30) { p.play { random() } }
        // Shrink to the two or three most recently played presets: history would exclude them all.
        val recent = ArrayList<String>()
        repeat(3) { recent += p.play { random() } }
        for (size in listOf(2, 3)) {
            p.update(inputs(cat, scope = Scope.Favorites, favorites = recent.takeLast(size).toSet()))
            repeat(20) {
                val before = p.current?.key
                val r = p.random()
                val key = presetKey(r)
                assertTrue(key in recent.takeLast(size))
                assertNotEquals(before, key)
                p.onLoaded(r.requestId)
            }
        }
        assertTrue(keys.containsAll(recent))
    }

    @Test fun random_ineligibleHistoryNotCounted() {
        val cat = flatCatalog(5)
        val (p0, p1, p2, p3, p4) = keysOf(cat)
        for (seed in 0 until 20) {
            val p = playlist(cat, seed)
            listOf(p3, p0, p1, p2).forEach { k -> p.play { select(k)!! } }
            p.update(inputs(cat, skipped = setOf(p1)))
            // n = 4, k = 2: exclude current p2 and the two most recent eligible entries p0, p3.
            assertEquals(p4, presetKey(p.random()))
        }
    }

    // ---- Navigation ----

    @Test fun next_visitsEveryCandidateOncePerCycle() {
        val cat = flatCatalog(7)
        val p = playlist(cat)
        repeat(3) {
            val cycle = (1..7).map { p.play { next() } }
            assertEquals(keysOf(cat).toSet(), cycle.toSet())
        }
    }

    @Test fun next_cycleBoundaryNeverRepeatsCurrent() {
        for (n in 2..5) {
            val cat = flatCatalog(n)
            val p = playlist(cat)
            val seq = (1..n * 20).map { p.play { next() } }
            seq.zipWithNext().forEach { (a, b) -> assertNotEquals("n=$n", a, b) }
            seq.chunked(n).forEach { assertEquals(keysOf(cat).toSet(), it.toSet()) }
        }
    }

    @Test fun next_updateDropsIneligibleAndAddsNew() {
        val cat = flatCatalog(6)
        val p = playlist(cat)
        val first = p.play { next() }
        val dropped = keysOf(cat).first { it != first }
        val grown = catalogOf(cat.packs.single(), pack("user:new", "n.milk"))
        p.update(inputs(grown, skipped = setOf(dropped)))
        val rest = (1..5).map { p.play { next() } }
        val expected = keysOf(cat).toSet() - first - dropped + "user:new/n.milk"
        assertEquals(expected, rest.toSet())
        assertEquals(5, rest.size)
    }

    @Test fun previous_walksHistorySkippingIneligible() {
        val cat = flatCatalog(4)
        val (p0, p1, p2, p3) = keysOf(cat)
        val p = playlist(cat)
        listOf(p0, p1, p2, p3).forEach { k -> p.play { select(k)!! } }
        p.update(inputs(cat, skipped = setOf(p1)))
        assertEquals(p2, p.play { previous()!! })
        assertEquals(p0, p.play { previous()!! })
        assertNull(p.previous())
    }

    @Test fun previous_atStart_returnsNull() {
        val p = playlist(flatCatalog(3))
        assertNull(p.previous())
        p.play { next() }
        assertNull(p.previous())
        assertEquals(1L, p.latestRequestId)
    }

    @Test fun select_unknownKey_returnsNull() {
        val p = playlist(flatCatalog(3))
        assertNull(p.select("bundled:p/nope.milk"))
        assertEquals(0L, p.latestRequestId)
        assertNull(p.current)
    }

    @Test fun latestRequestId_tracksMostRecentIssue() {
        val cat = flatCatalog(4)
        val p = playlist(cat)
        assertEquals(0L, p.latestRequestId)
        val a = p.next(); assertEquals(1L, a.requestId); assertEquals(1L, p.latestRequestId)
        val b = p.random(); assertEquals(2L, b.requestId); assertEquals(2L, p.latestRequestId)
        val c = p.select(keysOf(cat)[0])!!; assertEquals(3L, c.requestId); assertEquals(3L, p.latestRequestId)
        assertNull(p.previous()); assertEquals(3L, p.latestRequestId)
        val d = p.onLoadFailed(c.requestId)!!; assertEquals(4L, d.requestId); assertEquals(4L, p.latestRequestId)
        val e = p.start(null); assertEquals(5L, e.requestId); assertEquals(5L, p.latestRequestId)
    }

    // ---- Failures ----

    @Test fun loadFailed_marksBrokenTriesNext() {
        val cat = flatCatalog(3)
        val bad = keysOf(cat)[0]
        val p = playlist(cat)
        val r = p.select(bad)!!
        val retry = p.onLoadFailed(r.requestId)!!
        assertNotEquals(bad, presetKey(retry))
        p.onLoaded(retry.requestId)
        repeat(20) {
            assertNotEquals(bad, p.play { next() })
            assertNotEquals(bad, p.play { random() })
        }
    }

    @Test fun allCandidatesFail_fallsBackToIdleAfterFive() {
        val p = playlist(flatCatalog(10))
        var r = p.start(null)
        val tried = mutableListOf(presetKey(r))
        repeat(4) {
            r = p.onLoadFailed(r.requestId)!!
            tried += presetKey(r)
        }
        assertEquals(5, tried.toSet().size)
        assertIdle(p.onLoadFailed(r.requestId)!!)
        assertNull(p.current)
    }

    @Test fun onLoaded_resetsFailureCounter() {
        val p = playlist(flatCatalog(10))
        var r = p.start(null)
        repeat(4) { r = p.onLoadFailed(r.requestId)!!; presetKey(r) }
        p.onLoaded(r.requestId)
        r = p.next()
        repeat(4) { r = p.onLoadFailed(r.requestId)!!; presetKey(r) }
        assertIdle(p.onLoadFailed(r.requestId)!!)
    }

    @Test fun staleIds_ignored() {
        val p = playlist(flatCatalog(5))
        val r1 = p.next()
        val r2 = p.next()
        val current = p.current
        assertNull(p.onLoadFailed(r1.requestId))
        p.onLoaded(r1.requestId)
        assertEquals(r2.requestId, p.latestRequestId)
        assertEquals(current, p.current)
        p.onLoaded(r2.requestId)
        // r1 never entered history, so there is nothing to go back to.
        assertNull(p.previous())
        // Stale failure did not mark r1 broken: it is still reachable.
        assertTrue(keyOf(r1)!! in p.reachable())
    }

    @Test fun currentPresetRemoved_nextPicksFromRemaining() {
        val cat = catalogOf(pack("bundled:p", "p0.milk", "p1.milk", "p2.milk"), pack("user:u", "u0.milk", "u1.milk"))
        val p = playlist(cat)
        p.play { select("user:u/u0.milk")!! }
        val shrunk = catalogOf(cat.pack("bundled:p")!!)
        p.update(inputs(shrunk))
        assertEquals("user:u/u0.milk", p.current?.key)
        repeat(10) { assertTrue(p.play { next() }.startsWith("bundled:p/")) }
    }

    @Test fun start_lastKeyMissing_picksNext() {
        val cat = flatCatalog(4)
        val p = playlist(cat)
        val r = p.start("user:gone/x.milk")
        assertTrue(presetKey(r) in keysOf(cat))
        val q = playlist(cat, configure = { it.copy(skipped = setOf(keysOf(cat)[1])) }).start(keysOf(cat)[1])
        assertNotEquals(keysOf(cat)[1], presetKey(q))
    }

    @Test fun start_lastKeyPresent_playsIt() {
        val cat = flatCatalog(4)
        val p = playlist(cat)
        val r = p.start(keysOf(cat)[3])
        assertEquals(keysOf(cat)[3], presetKey(r))
        assertEquals(keysOf(cat)[3], p.current?.key)
    }

    /** A preset skipped for lack of capacity stays in the rotation; a failed one does not. */
    @Test fun skippedWithoutBroken_staysReachable_butFailedDoesNot() {
        val p = playlist(flatCatalog(4))
        val skipped = p.next()
        p.onLoadFailed(skipped.requestId, markBroken = false)
        assertTrue(presetKey(skipped) in p.reachable())
        val failed = p.next()
        p.onLoadFailed(failed.requestId)
        assertFalse(presetKey(failed) in p.reachable())
    }

    // ---- Request ids: never below the renderer's current request, whoever loaded it ----

    /** Ids never fall below the renderer's current request, whoever loaded it. */
    @Test fun ids_neverFallBelowTheFloor() {
        var floor = 0L
        val p = Playlist(Random(42)) { floor }.also { it.update(inputs(flatCatalog(5))) }
        val first = p.next().requestId
        floor = first + 1_000 // another loader's request is on screen
        val second = p.next().requestId
        assertTrue("id $second must be above the floor $floor", second > floor)
        assertEquals(second + 1, p.next().requestId) // consecutive again afterwards
    }

    /** A reissue keeps the target under a new id above the floor; [current] is unchanged. */
    @Test fun reissueLatest_keepsTargetAndHistoryPosition() {
        var floor = 0L
        val p = Playlist(Random(42)) { floor }.also { it.update(inputs(mixed)) }
        val r = p.next()
        floor = r.requestId + 1_000
        val again = p.reissueLatest()!!
        assertTrue(again.requestId > floor)
        assertEquals(r.target, again.target)
        assertEquals(r.notice, again.notice)
        assertEquals(again.requestId, p.latestRequestId)
        assertEquals(presetKey(r), p.current?.key)
    }

    /** A reissued history step keeps the history walk (no duplicate entry). */
    @Test fun reissueLatest_keepsHistoryNavigation() {
        var floor = 0L
        val p = Playlist(Random(42)) { floor }.also { it.update(inputs(flatCatalog(5))) }
        val a = p.play { next() }
        val b = p.play { next() }
        p.play { next() }
        val back = p.previous()!!
        assertEquals(b, presetKey(back))
        floor = back.requestId + 1_000
        val again = p.reissueLatest()!!
        p.onLoaded(again.requestId)
        assertEquals(a, presetKey(p.previous()!!))
    }

    // ---- Transition presets never play; skip ----

    private val mixed = catalogOf(pack("bundled:a", "p0.milk", "p1.milk", "p2.milk", "p3.milk", "p4.milk", "!t/t0.milk", "!t/t1.milk"))
    private val transitions = setOf("bundled:a/!t/t0.milk", "bundled:a/!t/t1.milk")
    /** The author removed interstitials (2026-10-13): a transition preset is never issued, whatever the catalog holds. */
    @Test fun next_neverPlaysATransitionPreset() {
        val p = playlist(mixed)
        val seen = HashSet<String>()
        repeat(50) {
            val r = p.next()
            p.onLoaded(r.requestId)
            val k = keyOf(r)!!
            assertFalse("transition preset issued by next(): $k", k in transitions)
            seen += k
        }
        assertEquals("the cycle still visits every normal preset", setOf("bundled:a/p0.milk", "bundled:a/p1.milk", "bundled:a/p2.milk", "bundled:a/p3.milk", "bundled:a/p4.milk"), seen)
    }

    /** Skip: excluded at once (before the persisted set arrives), from ▶ next and 🔀 alike; eligible again once un-skipped. */
    @Test fun skip_excludesAtOnce_untilUnskipped() {
        val p = playlist(mixed)
        val skipped = "bundled:a/p0.milk"
        p.play { select(skipped)!! }
        val s = p.skipCurrent()
        assertNotEquals(skipped, presetKey(s))
        p.onLoaded(s.requestId)
        // Excluded at once, before the persisted skip set arrives through update.
        repeat(12) { assertNotEquals(skipped, presetKey(p.advance())) }
        repeat(12) { assertNotEquals(skipped, p.play { random() }) }
        p.update(inputs(mixed, skipped = setOf(skipped)))
        repeat(12) { assertNotEquals(skipped, presetKey(p.advance())) }
        // Un-skipping (the persisted set drops it) makes it eligible again.
        p.update(inputs(mixed))
        assertTrue(skipped in p.reachable())
    }
}
