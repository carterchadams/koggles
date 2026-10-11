package dev.koggles.presets

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDocTreeTest {

    /** Paths are asset-relative; a directory is any path with entries under it. */
    private class FakeAssetSource(private val files: Map<String, ByteArray>) : AssetSource {
        val listCalls = mutableListOf<String>()

        override fun list(path: String): Array<String> {
            listCalls += path
            val prefix = if (path.isEmpty()) "" else "$path/"
            return files.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct().sorted().toTypedArray()
        }

        override fun open(path: String): InputStream =
            ByteArrayInputStream(files[path] ?: throw FileNotFoundException(path))
    }

    @Test
    fun filesRecognizedByExtensionWithoutListing() {
        val files = (0 until 1000).associate { "presets/Pack/p$it.milk" to ByteArray(0) }
        val src = FakeAssetSource(files)
        val tree = AssetDocTree(src, "presets/Pack")

        val kids = tree.children(tree.root)

        assertEquals(1000, kids.size)
        assertTrue(kids.none { it.isDir })
        assertEquals(1, src.listCalls.size)
    }

    @Test
    fun unknownNamesProbedAsDirs() {
        val src = FakeAssetSource(
            mapOf(
                "presets/Pack/Fractal/a.milk" to ByteArray(0),
                "presets/Pack/b.MILK" to ByteArray(0),
                "presets/Pack/notes.txt" to ByteArray(0),
            ),
        )
        val tree = AssetDocTree(src, "presets/Pack")

        val kids = tree.children(tree.root).associateBy { it.name }

        assertTrue(kids.getValue("Fractal").isDir)
        assertFalse(kids.getValue("b.MILK").isDir)
        assertFalse(kids.getValue("notes.txt").isDir)
        // Root listing plus one probe of the unknown name "Fractal" only.
        assertEquals(listOf("presets/Pack", "presets/Pack/Fractal"), src.listCalls)
        val nested = tree.children(kids.getValue("Fractal"))
        assertEquals(listOf("a.milk"), nested.map { it.name })
        assertEquals("presets/Pack/Fractal/a.milk", nested.single().id)
    }

    @Test
    fun readTextReadsUtf8() {
        val text = "per_frame_1=zoom=1.01; // Ünïcødé ✓"
        val src = FakeAssetSource(mapOf("presets/Pack/u.milk" to text.toByteArray(Charsets.UTF_8)))
        val tree = AssetDocTree(src, "presets/Pack")

        val node = tree.children(tree.root).single()

        assertEquals(text, tree.readText(node))
    }

    @Test
    fun emptyListMeansFile() {
        val src = FakeAssetSource(mapOf("presets/Pack/LICENSE" to ByteArray(3)))
        val tree = AssetDocTree(src, "presets/Pack")

        val node = tree.children(tree.root).single()

        assertEquals("LICENSE", node.name)
        assertFalse(node.isDir)
        assertEquals(listOf("presets/Pack", "presets/Pack/LICENSE"), src.listCalls)
    }
}
