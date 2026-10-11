package dev.koggles.presets

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextureInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(
        private val files: Map<String, ByteArray> = mapOf(
            "a.jpg" to byteArrayOf(1, 2, 3),
            "b.png" to byteArrayOf(4, 5),
            "c.JPG" to byteArrayOf(6),
        ),
        /** Throws on open once this many files have been opened. */
        private val failAfterOpens: Int = Int.MAX_VALUE,
    ) : AssetSource {
        var opens = 0

        override fun list(path: String): Array<String> =
            if (path == "textures") files.keys.sorted().toTypedArray() else emptyArray()

        override fun open(path: String): InputStream {
            if (opens >= failAfterOpens) throw IOException("interrupted")
            opens++
            val bytes = files[path.removePrefix("textures/")] ?: throw FileNotFoundException(path)
            return ByteArrayInputStream(bytes)
        }
    }

    private class FakeFs(
        var failRename: (File, File) -> Boolean = { _, _ -> false },
        var failWrite: (File) -> Boolean = { false },
        var onRename: (File, File) -> Unit = { _, _ -> },
    ) : InstallerFs {
        override fun rename(from: File, to: File): Boolean {
            onRename(from, to)
            if (failRename(from, to)) return false
            return RealInstallerFs.rename(from, to)
        }

        override fun writeText(f: File, s: String) {
            if (failWrite(f)) throw IOException("disk full")
            RealInstallerFs.writeText(f, s)
        }
    }

    private val filesDir get() = tmp.root
    private val base get() = File(filesDir, "bundled-textures")
    private fun current() = File(base, "current").readText()

    private fun install(key: Long, src: AssetSource = FakeSource(), fs: InstallerFs = RealInstallerFs) =
        TextureInstaller(src, filesDir, key, fs).installIfNeeded()

    @Test
    fun firstRun_extractsAndPublishes() {
        val dir = install(1)

        assertEquals(File(base, "v1"), dir)
        assertEquals("v1", current())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "a.jpg").readBytes())
        assertEquals(setOf("a.jpg", "b.png", "c.JPG"), dir.list()!!.toSet())
        assertEquals(setOf("current", "v1"), base.list()!!.toSet())
    }

    @Test
    fun sameKey_skipsExtraction() {
        install(1)
        val src = FakeSource()

        val dir = install(1, src)

        assertEquals(File(base, "v1"), dir)
        assertEquals(0, src.opens)
    }

    @Test
    fun upgrade_withNonEmptyPrevious_publishesNew() {
        install(1)
        assertTrue(File(base, "v1").list()!!.isNotEmpty())

        val dir = install(2)

        assertEquals(File(base, "v2"), dir)
        assertEquals("v2", current())
        assertFalse(File(base, "v1").exists())
        assertEquals(setOf("current", "v2"), base.list()!!.toSet())
    }

    @Test
    fun interruptedExtraction_keepsPrevious() {
        install(1)

        val dir = install(2, FakeSource(failAfterOpens = 1))

        assertEquals(File(base, "v1"), dir)
        assertEquals("v1", current())
        assertTrue(File(base, "v1").isDirectory)
        assertTrue(File(base, "v2.tmp").exists())
        assertFalse(File(base, "v2").exists())

        val next = install(3)

        assertEquals(File(base, "v3"), next)
        assertFalse(File(base, "v2.tmp").exists())
        assertEquals(setOf("current", "v3"), base.list()!!.toSet())
    }

    @Test
    fun failedDirRename_keepsPrevious() {
        install(1)
        val fs = FakeFs(failRename = { from, _ -> from.name == "v2.tmp" })

        val dir = install(2, fs = fs)

        assertEquals(File(base, "v1"), dir)
        assertEquals("v1", current())
        assertTrue(File(base, "v1").isDirectory)
        assertFalse(File(base, "v2").exists())
    }

    @Test
    fun failedPointerPublish_keepsPrevious_thenRecovers() {
        install(1)
        val fs = FakeFs(failRename = { from, _ -> from.name == "current.tmp" })

        val dir = install(2, fs = fs)

        assertEquals(File(base, "v1"), dir)
        assertEquals("v1", current())
        assertTrue(File(base, "v1").isDirectory)

        val src = FakeSource()
        val recovered = install(2, src)

        assertEquals(File(base, "v2"), recovered)
        assertEquals("v2", current())
        assertEquals(setOf("a.jpg", "b.png", "c.JPG"), recovered.list()!!.toSet())
        assertEquals(setOf("current", "v2"), base.list()!!.toSet())
    }

    @Test
    fun cleanupOnlyAfterPublish() {
        install(1)
        val v1 = File(base, "v1")
        val stillThereAtPublish = mutableListOf<Boolean>()
        val fs = FakeFs(onRename = { from, _ -> stillThereAtPublish += v1.isDirectory })

        install(2, fs = fs)

        // Both renames (dir, then pointer) happened while v1 was intact; v1 went only afterwards.
        assertEquals(listOf(true, true), stillThereAtPublish)
        assertFalse(v1.exists())

        // A failed pointer write never triggers cleanup.
        val v2 = File(base, "v2")
        install(3, fs = FakeFs(failWrite = { true }))
        assertTrue(v2.isDirectory)
        assertEquals("v2", current())
    }

    @Test
    fun pointerToMissingDir_reinstalls() {
        install(1)
        File(base, "v1").deleteRecursively()
        val src = FakeSource()

        val dir = install(1, src)

        assertEquals(File(base, "v1"), dir)
        assertTrue(dir.isDirectory)
        assertEquals(3, src.opens)
        assertEquals("v1", current())
    }

    @Test
    fun noPreviousAndFailure_returnsNullDirGracefully() {
        val dir = install(1, FakeSource(failAfterOpens = 0))

        assertFalse(dir.exists())
        assertFalse(File(base, "current").exists())
    }

    @Test
    fun concurrentInstalls_areSerialized_andPublishCompleteDirectory() {
        // A slow source: each open sleeps, so unsynchronized installers would interleave.
        class SlowSource : AssetSource {
            val active = java.util.concurrent.atomic.AtomicInteger(0)
            val maxActive = java.util.concurrent.atomic.AtomicInteger(0)
            override fun list(path: String) = if (path == "textures") arrayOf("a.jpg", "b.png") else emptyArray()
            override fun open(path: String): InputStream {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(50)
                active.decrementAndGet()
                return ByteArrayInputStream(byteArrayOf(7))
            }
        }
        val src = SlowSource()
        val results = java.util.Collections.synchronizedList(mutableListOf<File>())
        val threads = (1..4).map { kotlin.concurrent.thread { results += TextureInstaller(src, filesDir, 3).installIfNeeded() } }
        threads.forEach { it.join() }
        assertEquals("never two extractions at once", 1, src.maxActive.get())
        assertTrue(results.all { it == results.first() })
        assertTrue(File(results.first(), "a.jpg").isFile && File(results.first(), "b.png").isFile)
    }
}
