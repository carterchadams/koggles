package dev.koggles.presets

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDocTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun index(): List<Pack> = runBlocking {
        (PresetIndexer(Dispatchers.Unconfined).index(FileDocTree(tmp.root), "user").last() as IndexProgress.Done).packs
    }

    @Test
    fun selfAndAncestorSymlinks_areSkipped_traversalTerminates() {
        val pack = tmp.newFolder("A")
        pack.resolve("x.milk").writeText("x")
        val sub = pack.resolve("sub").apply { mkdir() }
        sub.resolve("y.milk").writeText("y")
        Files.createSymbolicLink(pack.resolve("loop1").toPath(), pack.toPath())
        Files.createSymbolicLink(sub.resolve("loop2").toPath(), pack.toPath())
        val keys = index().single().presets.map { it.key }
        assertEquals(listOf("user:A/sub/y.milk", "user:A/x.milk"), keys.sorted())
    }

    @Test
    fun symlinkOutsideRoot_isSkipped() {
        val outside = tmp.newFolder("outside-root-target")
        outside.resolve("z.milk").writeText("z")
        val pack = tmp.newFolder("B")
        Files.createSymbolicLink(pack.resolve("ext").toPath(), outside.toPath())
        Files.createSymbolicLink(pack.resolve("ext.milk").toPath(), outside.resolve("z.milk").toPath())
        val b = index().single { it.id == "user:B" }
        assertEquals(emptyList<String>(), b.presets.map { it.key })
    }
}
