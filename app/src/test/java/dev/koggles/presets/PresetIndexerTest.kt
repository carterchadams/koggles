package dev.koggles.presets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresetIndexerTest {

    private fun indexAll(tree: DocTree, prefix: String = "user"): List<Pack> {
        var packs: List<Pack> = emptyList()
        runTest {
            val last = PresetIndexer(StandardTestDispatcher(testScheduler)).index(tree, prefix).last()
            packs = (last as IndexProgress.Done).packs
        }
        return packs
    }

    private fun Pack.preset(relPath: String): PresetRef =
        presets.single { it.relPath == relPath }

    @Test
    fun topLevelFoldersBecomePacks() {
        val tree = FakeDocTree(listOf("A/a.milk", "B/b.milk", "C/x/c.milk"))
        val packs = indexAll(tree, "user")
        assertEquals(listOf("user:A", "user:B", "user:C"), packs.map { it.id })
        assertEquals(listOf("A", "B", "C"), packs.map { it.name })
        assertTrue(packs.all { it.tree === tree })
        assertEquals(listOf(1, 1, 1), packs.map { it.presets.size })
    }

    @Test
    fun keysUseSlashAndPackPrefix() {
        val packs = indexAll(FakeDocTree(listOf("Cream/sub/deep/p.milk")), "bundled")
        val p = packs.single().presets.single()
        assertEquals("bundled:Cream", p.packId)
        assertEquals("sub/deep/p.milk", p.relPath)
        assertEquals("bundled:Cream/sub/deep/p.milk", p.key)
        assertEquals("p", p.name)
        assertEquals("Cream/sub/deep/p.milk", p.node.id)
    }

    @Test
    fun nestedFolderRecorded() {
        val pack = indexAll(FakeDocTree(listOf("P/top.milk", "P/a/mid.milk", "P/a/b/low.milk"))).single()
        assertEquals("", pack.preset("top.milk").folder)
        assertEquals("a", pack.preset("a/mid.milk").folder)
        assertEquals("a/b", pack.preset("a/b/low.milk").folder)
    }

    @Test
    fun bangSegmentAnyDepthIsTransition() {
        val pack = indexAll(
            FakeDocTree(listOf("P/!t.milk", "P/!Trans/x.milk", "P/a/!b/c/y.milk", "P/a/plain.milk", "P/a/mid!dle.milk")),
        ).single()
        assertTrue(pack.preset("!t.milk").isTransition)
        assertTrue(pack.preset("!Trans/x.milk").isTransition)
        assertTrue(pack.preset("a/!b/c/y.milk").isTransition)
        assertFalse(pack.preset("a/plain.milk").isTransition)
        assertFalse(pack.preset("a/mid!dle.milk").isTransition)
    }

    @Test
    fun texturesCollectedCaseInsensitive() {
        val pack = indexAll(
            FakeDocTree(listOf("P/a.JPG", "P/t/b.png", "P/t/deep/c.TgA", "P/d.Dds", "P/e.jpeg", "P/f.bmp", "P/p.milk")),
        ).single()
        assertEquals(
            setOf("a.JPG", "t/b.png", "t/deep/c.TgA", "d.Dds", "e.jpeg", "f.bmp"),
            pack.textures.map { it.first }.toSet(),
        )
        assertEquals(6, pack.textures.size)
        assertEquals("P/t/deep/c.TgA", pack.textures.single { it.first == "t/deep/c.TgA" }.second.id)
        assertEquals(1, pack.presets.size)
    }

    @Test
    fun otherFilesIgnored() {
        val pack = indexAll(
            FakeDocTree(listOf("P/readme.txt", "P/a.milk.bak", "P/noext", "P/x/notes.md", "P/ok.MILK")),
        ).single()
        assertEquals(listOf("ok.MILK"), pack.presets.map { it.relPath })
        assertEquals("ok", pack.preset("ok.MILK").name)
        assertTrue(pack.textures.isEmpty())
        assertTrue(pack.readErrors.isEmpty())
    }

    @Test
    fun rootFilesIgnored() {
        val packs = indexAll(FakeDocTree(listOf("loose.milk", "loose.png", "P/p.milk")))
        assertEquals(listOf("user:P"), packs.map { it.id })
        assertEquals(listOf("p.milk"), packs.single().presets.map { it.relPath })
    }

    @Test
    fun childrenThrows_recordsErrorAndContinues() {
        val tree = FakeDocTree(
            listOf("A/bad/x.milk", "A/good/y.milk", "A/z.milk", "B/b.milk", "C/c.milk"),
            failingDirs = setOf("A/bad", "B"),
        )
        val packs = indexAll(tree)
        assertEquals(listOf("user:A", "user:B", "user:C"), packs.map { it.id })
        val a = packs[0]
        assertEquals(setOf("good/y.milk", "z.milk"), a.presets.map { it.relPath }.toSet())
        assertEquals(1, a.readErrors.size)
        assertTrue(a.readErrors.single(), a.readErrors.single().contains("bad"))
        val b = packs[1]
        assertTrue(b.presets.isEmpty())
        assertEquals(1, b.readErrors.size)
        assertEquals(listOf("c.milk"), packs[2].presets.map { it.relPath })
        assertTrue(packs[2].readErrors.isEmpty())
    }

    @Test
    fun readTextNotCalledDuringIndex() {
        val tree = FakeDocTree(listOf("A/a.milk", "A/x/b.milk", "A/t.png", "B/c.milk"))
        val packs = indexAll(tree)
        assertEquals(3, packs.sumOf { it.presets.size })
        assertEquals(0, tree.readTextCalls)
    }

    @Test
    fun partialPerPackThenDone() = runTest {
        val tree = FakeDocTree(listOf("A/a.milk", "B/b.milk", "C/c.milk"))
        val events = PresetIndexer(StandardTestDispatcher(testScheduler)).index(tree, "user").toList()
        assertEquals(4, events.size)
        val partials = events.take(3).map { it as IndexProgress.Partial }
        assertEquals(listOf(listOf("user:A"), listOf("user:A", "user:B"), listOf("user:A", "user:B", "user:C")),
            partials.map { p -> p.packs.map { it.id } })
        val done = events.last() as IndexProgress.Done
        assertEquals(listOf("user:A", "user:B", "user:C"), done.packs.map { it.id })
    }

    @Test
    fun cancellationStopsWalking() = runTest {
        val files = (0 until 5).flatMap { p -> (0 until 5).map { d -> "P$p/d$d/x.milk" } }
        val tree = FakeDocTree(files)
        val indexer = PresetIndexer(StandardTestDispatcher(testScheduler))
        var callsAtCancel = -1
        val job = launch {
            indexer.index(tree, "user").collect {
                if (it is IndexProgress.Partial && callsAtCancel < 0) {
                    callsAtCancel = tree.childrenCalls
                    cancel()
                }
            }
        }
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        // Root + first pack + its 5 subfolders.
        assertEquals(7, callsAtCancel)
        assertEquals(callsAtCancel, tree.childrenCalls)
    }

    @Test
    fun catalogByKeyFindsPreset() {
        val packs = indexAll(FakeDocTree(listOf("A/x/a.milk", "B/b.milk")))
        val catalog = Catalog(packs)
        val ref = catalog.byKey("user:A/x/a.milk")
        assertNotNull(ref)
        assertSame(packs[0].presets.single(), ref)
        assertSame(packs[1].presets.single(), catalog.byKey("user:B/b.milk"))
        assertNull(catalog.byKey("user:A/missing.milk"))
        assertSame(packs[1], catalog.pack("user:B"))
        assertNull(catalog.pack("user:Z"))
    }
}
