package dev.koggles.presets

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedReadTest {
    @get:Rule val tmp = TemporaryFolder()

    /** An endless stream of 'a' that counts what was consumed. */
    private class CountingStream(private val total: Long) : InputStream() {
        var consumed = 0L
        override fun read(): Int = if (consumed >= total) -1 else { consumed++; 'a'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= total) return -1
            val n = minOf(len.toLong(), total - consumed).toInt()
            b.fill('a'.code.toByte(), off, off + n)
            consumed += n
            return n
        }
    }

    @Test fun exactlyLimit_reads() {
        val text = ByteArrayInputStream(ByteArray(MAX_PRESET_BYTES) { 'x'.code.toByte() }).readPresetText()
        assertEquals(MAX_PRESET_BYTES, text.length)
    }

    @Test fun limitPlusOne_throwsIOException() {
        assertThrows(IOException::class.java) {
            ByteArrayInputStream(ByteArray(MAX_PRESET_BYTES + 1)).readPresetText()
        }
    }

    @Test fun hugeStream_readsAtMostLimitPlusOne() {
        val s = CountingStream(64L * 1024 * 1024)
        assertThrows(IOException::class.java) { s.readPresetText() }
        assertTrue("consumed ${s.consumed}", s.consumed <= MAX_PRESET_BYTES + 1L)
    }

    @Test fun fileTree_oversized_throwsIOException() {
        val f = tmp.newFile("big.milk").apply { writeBytes(ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }) }
        val tree = FileDocTree(tmp.root)
        val node = tree.children(tree.root).single { it.name == "big.milk" }
        assertThrows(IOException::class.java) { tree.readText(node) }
        assertTrue(File(f.path).exists())
    }
}
