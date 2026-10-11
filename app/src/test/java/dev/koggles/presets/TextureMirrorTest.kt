package dev.koggles.presets

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextureMirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tree = FakeDocTree(emptyList())

    private fun node(relPath: String, size: Long = 1, mtime: Long = 0) =
        DocNode("src/$relPath", relPath.substringAfterLast('/'), isDir = false, size = size, lastModified = mtime)

    private fun pack(id: String, vararg textures: DocNode) = Pack(
        id = id,
        name = id,
        tree = tree,
        presets = emptyList(),
        textures = textures.map { it.id.removePrefix("src/") to it },
        readErrors = emptyList(),
    )

    private fun entry(n: DocNode) = MirrorEntry(n.id, n.size, n.lastModified)

    private val copied = mutableListOf<String>()
    private var failOn: (DocNode) -> Boolean = { false }
    private val copyFn: (DocNode, File) -> Unit = { n, f ->
        copied += n.id
        if (failOn(n)) {
            f.writeText("partial")
            throw IOException("read failed: ${n.id}")
        }
        f.writeText(n.id)
    }

    private fun mirror() = TextureMirror(tmp.root, copyFn)

    private fun manifestOf(dir: File): MirrorManifest =
        Json.decodeFromString(MirrorManifest.serializer(), File(dir, "manifest.json").readText())

    @Test
    fun collisionFirstSortedPathWins() {
        val b = node("z/tex.jpg")
        val a = node("a/tex.jpg")
        val c = node("m/tex.jpg")
        val other = node("other.png")
        val plan = mirror().plan(pack("user:p", b, other, c, a), MirrorManifest(emptyMap()), dirExists = true)
        assertEquals(listOf(a to "tex.jpg", other to "other.png"), plan.copy)
        assertEquals(listOf("m/tex.jpg", "z/tex.jpg"), plan.collisions)
        assertEquals(emptyList<String>(), plan.delete)
    }

    @Test
    fun unchangedEntrySkipsCopy() {
        val a = node("a.jpg", size = 10, mtime = 5)
        val manifest = MirrorManifest(mapOf("a.jpg" to entry(a)))
        val plan = mirror().plan(pack("user:p", a), manifest, dirExists = true)
        assertEquals(emptyList<Pair<DocNode, String>>(), plan.copy)
        assertEquals(emptyList<String>(), plan.delete)
    }

    @Test
    fun changedSizeOrMtimeRecopies() {
        val old = node("a.jpg", size = 10, mtime = 5)
        val manifest = MirrorManifest(mapOf("a.jpg" to entry(old), "b.jpg" to entry(node("b.jpg", 3, 3))))
        val bigger = node("a.jpg", size = 11, mtime = 5)
        val newer = node("b.jpg", size = 3, mtime = 4)
        val plan = mirror().plan(pack("user:p", bigger, newer), manifest, dirExists = true)
        assertEquals(listOf(bigger to "a.jpg", newer to "b.jpg"), plan.copy)
    }

    @Test
    fun removedSourceDeleted() {
        val a = node("a.jpg")
        val gone = node("gone.png")
        val m = mirror()
        val dir = m.apply(pack("user:p", a, gone))
        assertTrue(File(dir, "gone.png").isFile)

        val plan = m.plan(pack("user:p", a), manifestOf(dir), dirExists = true)
        assertEquals(listOf("gone.png"), plan.delete)
        assertEquals(emptyList<Pair<DocNode, String>>(), plan.copy)

        m.apply(pack("user:p", a))
        assertFalse(File(dir, "gone.png").exists())
        assertTrue(File(dir, "a.jpg").isFile)
        assertEquals(setOf("a.jpg"), manifestOf(dir).files.keys)
    }

    @Test
    fun evictedDirectoryRebuildsAll() {
        val a = node("a.jpg")
        val b = node("d/b.png")
        val manifest = MirrorManifest(mapOf("a.jpg" to entry(a), "b.png" to entry(b)))
        val plan = mirror().plan(pack("user:p", a, b), manifest, dirExists = false)
        assertEquals(listOf(a to "a.jpg", b to "b.png"), plan.copy)

        // End to end: deleting the directory (cache eviction) rebuilds it on the next apply.
        val m = mirror()
        val dir = m.apply(pack("user:p", a, b))
        dir.deleteRecursively()
        copied.clear()
        m.apply(pack("user:p", a, b))
        assertEquals(listOf(a.id, b.id), copied)
        assertEquals("src/a.jpg", File(dir, "a.jpg").readText())
        assertEquals(setOf("a.jpg", "b.png"), manifestOf(dir).files.keys)
    }

    @Test
    fun copyFailureLeavesNoFinalFileAndNoManifestEntry() {
        val a = node("a.jpg")
        val bad = node("bad.png")
        val c = node("c.jpg")
        failOn = { it == bad }
        val dir = mirror().apply(pack("user:p", a, bad, c))
        assertFalse(File(dir, "bad.png").exists())
        assertFalse(File(dir, "bad.png.tmp").exists())
        assertTrue(File(dir, "a.jpg").isFile)
        assertTrue(File(dir, "c.jpg").isFile)
        assertFalse(File(dir, "manifest.json.tmp").exists())
        assertEquals(mapOf("a.jpg" to entry(a), "c.jpg" to entry(c)), manifestOf(dir).files)

        // The next apply retries only the failed file.
        failOn = { false }
        copied.clear()
        mirror().apply(pack("user:p", a, bad, c))
        assertEquals(listOf(bad.id), copied)
        assertTrue(File(dir, "bad.png").isFile)
    }

    @Test
    fun applyReturnsStableDirPerPack() {
        val a = node("a.jpg")
        val d1 = mirror().apply(pack("user:p", a))
        val d2 = mirror().apply(pack("user:p", a))
        val other = mirror().apply(pack("user:q", a))
        val hex = MessageDigest.getInstance("SHA-1").digest("user:p".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(File(File(tmp.root, "textures"), hex), d1)
        assertEquals(d1, d2)
        assertNotEquals(d1, other)
        assertTrue(d1.isDirectory)
    }

    @Test
    fun unsafeFlattenedNamesSkipped() {
        val dotdot = DocNode("src/x", "..", isDir = false, size = 1, lastModified = 0)
        val slash = DocNode("src/y", "a/b.jpg", isDir = false, size = 1, lastModified = 0)
        val ok = node("ok.jpg")
        val p = Pack("user:p", "p", tree, emptyList(), listOf("d/.." to dotdot, "e/a/b.jpg" to slash, "ok.jpg" to ok), emptyList())
        val plan = mirror().plan(p, MirrorManifest(emptyMap()), dirExists = true)
        assertEquals(listOf(ok to "ok.jpg"), plan.copy)
        assertEquals(listOf("d/..", "e/a/b.jpg"), plan.collisions)
        val dir = mirror().apply(p)
        assertEquals(listOf("manifest.json", "ok.jpg"), dir.list()!!.sorted())
    }

    @Test
    fun differentSourceTreeWithSameIds_recopies() {
        val x = node("x.png", size = 5, mtime = 7)
        val otherProvider = object : DocTree by tree {
            override fun sourceId(node: DocNode) = "content://other-provider/${node.id}"
        }
        val p = pack("user:p", x).copy(tree = otherProvider)
        val manifest = MirrorManifest(mapOf("x.png" to entry(x))) // recorded from the default tree
        val plan = TextureMirror(tmp.root, copyFn).plan(p, manifest, dirExists = true)
        assertEquals(listOf("x.png"), plan.copy.map { it.second })
    }

    @Test
    fun incompleteScan_doesNotDeleteCachedTextures() {
        val kept = node("a.png")
        val p = pack("user:p", kept).copy(readErrors = listOf("sub: provider timeout"))
        val manifest = MirrorManifest(mapOf("a.png" to entry(kept), "gone.png" to MirrorEntry("src/gone.png", 1, 0)))
        val plan = TextureMirror(tmp.root, copyFn).plan(p, manifest, dirExists = true)
        assertEquals(emptyList<String>(), plan.delete)
    }

    @Test
    fun evictedSingleFileWithSurvivingManifest_isRestored() {
        val a = node("a.jpg")
        val b = node("b.jpg")
        val dir = mirror().apply(pack("user:p", a, b))
        File(dir, "a.jpg").delete() // partial cache cleanup; manifest.json survives
        copied.clear()
        mirror().apply(pack("user:p", a, b))
        assertEquals(listOf(a.id), copied)
        assertTrue(File(dir, "a.jpg").isFile)
    }

    @Test
    fun manifestLostThenSourceRemoved_orphanDeletedOnCompleteScan() {
        val a = node("a.jpg")
        val b = node("b.jpg")
        val dir = mirror().apply(pack("user:p", a, b))
        File(dir, "manifest.json").delete()
        mirror().apply(pack("user:p", a)) // b removed at the source; complete scan
        assertTrue(File(dir, "a.jpg").isFile)
        assertTrue("orphan b.jpg removed", !File(dir, "b.jpg").exists())
    }

    @Test
    fun manifestLost_incompleteScan_keepsFiles() {
        val a = node("a.jpg")
        val b = node("b.jpg")
        val dir = mirror().apply(pack("user:p", a, b))
        File(dir, "manifest.json").delete()
        mirror().apply(pack("user:p", a).copy(readErrors = listOf("sub: timeout")))
        assertTrue(File(dir, "b.jpg").isFile)
    }

    @Test
    fun manifestLost_failedRecopyOfCurrentSource_keepsCachedFile_thenRetries() {
        val a = node("a.jpg")
        val dir = mirror().apply(pack("user:p", a))
        File(dir, "manifest.json").delete()
        failOn = { it.id == a.id }
        mirror().apply(pack("user:p", a)) // complete scan, but a's copy fails
        assertTrue("cached a.jpg survives a failed refresh", File(dir, "a.jpg").isFile)
        failOn = { false }
        copied.clear()
        mirror().apply(pack("user:p", a))
        assertEquals(listOf(a.id), copied)
    }
}
