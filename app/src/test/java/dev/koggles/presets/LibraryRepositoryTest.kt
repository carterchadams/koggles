package dev.koggles.presets

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    private val bundled = FakeDocTree(listOf("Cream/a.milk", "Cream/sub/b.milk"))
    private val user = FakeDocTree(listOf("Mine/x.milk", "Mine/tex/k.png"))
    private val bundledTex by lazy { tmp.newFolder("bundled-textures") }
    private val mirror by lazy { TextureMirror(tmp.newFolder("cache"), { _, f -> f.writeBytes(byteArrayOf(1)) }) }

    private fun repo(
        userTree: () -> DocTree? = { user },
        install: () -> File = { bundledTex },
        bundledTree: () -> DocTree = { bundled },
    ) = LibraryRepository(scope, bundledTree, install, userTree, mirror, PresetIndexer(Dispatchers.IO), Dispatchers.IO)

    private fun await(r: LibraryRepository, what: String, cond: (LibraryState) -> Boolean): LibraryState = runBlocking {
        withTimeout(10_000) { r.state.first(cond) }
    }

    private fun LibraryState.packIds() = catalog.packs.map { it.id }.toSet()

    @Test fun bundledIndexedThenReady() {
        val r = repo(userTree = { null })
        val s = await(r, "ready") { it.bundledReady }
        assertEquals(setOf("bundled:Cream"), s.packIds())
        assertEquals(bundledTex, s.bundledTextureDir)
    }

    @Test fun userPacksMergeAfterBundled() {
        val r = repo()
        val s = await(r, "user merged") { it.bundledReady && !it.userIndexing && "user:Mine" in it.packIds() }
        assertEquals(setOf("bundled:Cream", "user:Mine"), s.packIds())
        assertTrue(s.hasGrant)
    }

    @Test fun noGrant_userPacksAbsent() {
        val s = await(repo(userTree = { null }), "ready") { it.bundledReady && !it.userIndexing }
        assertFalse(s.hasGrant)
        assertEquals(setOf("bundled:Cream"), s.packIds())
    }

    @Test fun grantLost_dropsUserPacksKeepsBundled() {
        var tree: DocTree? = user
        val r = repo(userTree = { tree })
        await(r, "user merged") { "user:Mine" in it.packIds() && !it.userIndexing }
        tree = null
        r.refresh()
        val s = await(r, "user dropped") { "user:Mine" !in it.packIds() && !it.hasGrant }
        assertEquals(setOf("bundled:Cream"), s.packIds())
        assertTrue(s.packTextureDirs.isEmpty())
    }

    @Test fun refreshCancelsInFlightUserIndex() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : DocTree by user {
            override fun children(node: DocNode): List<DocNode> {
                if (node.id == "Mine") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return user.children(node)
            }
        }
        var tree: DocTree? = slow
        val r = repo(userTree = { tree })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        tree = FakeDocTree(listOf("Other/o.milk"))
        r.refresh()
        release.countDown()
        val s = await(r, "refreshed") { "user:Other" in it.packIds() && !it.userIndexing }
        assertFalse("stale index result must not land", "user:Mine" in s.packIds())
    }

    @Test fun texturePathsOwnPackThenBundled() = runBlocking {
        val r = repo()
        await(r, "mirrored") { "user:Mine" in it.packTextureDirs && !it.userIndexing }
        val paths = r.texturePathsFor("user:Mine")
        assertEquals(2, paths.size)
        assertTrue(paths[0].endsWith(r.state.value.packTextureDirs.getValue("user:Mine").name))
        assertEquals(bundledTex.absolutePath, paths[1])
        assertEquals(listOf(bundledTex.absolutePath), r.texturePathsFor("bundled:Cream"))
    }

    @Test fun evictedPackDirRemirroredBeforeReturn() = runBlocking {
        val r = repo()
        val s = await(r, "mirrored") { "user:Mine" in it.packTextureDirs && !it.userIndexing }
        val dir = s.packTextureDirs.getValue("user:Mine")
        dir.deleteRecursively()
        val paths = r.texturePathsFor("user:Mine")
        assertTrue(dir.isDirectory)
        assertTrue(File(dir, "k.png").isFile)
        assertEquals(dir.absolutePath, paths[0])
    }

    @Test fun installFailure_bundledTextureDirNull_stillReady() {
        val s = await(repo(install = { throw IOException("disk full") }, userTree = { null }), "ready") { it.bundledReady }
        assertNull(s.bundledTextureDir)
        assertTrue(s.errors.any { "disk full" in it })
    }

    @Test fun textureInstallGated_bundledReadyFalseUntilInstallSettles() {
        val gate = CountDownLatch(1)
        val r = repo(install = { gate.await(10, TimeUnit.SECONDS); bundledTex }, userTree = { null })
        // Indexing a tiny tree finishes quickly; readiness must still wait for the install.
        Thread.sleep(300)
        assertFalse(r.state.value.bundledReady)
        gate.countDown()
        val s = await(r, "ready") { it.bundledReady }
        assertEquals(bundledTex, s.bundledTextureDir)
    }

    @Test fun indexingSlowerThanInstall_readyAfterIndexing() {
        val gate = CountDownLatch(1)
        val slowBundled = object : DocTree by bundled {
            override fun children(node: DocNode): List<DocNode> { gate.await(10, TimeUnit.SECONDS); return bundled.children(node) }
        }
        val r = repo(bundledTree = { slowBundled }, userTree = { null })
        Thread.sleep(300)
        assertFalse(r.state.value.bundledReady)
        gate.countDown()
        val s = await(r, "ready") { it.bundledReady }
        assertEquals(setOf("bundled:Cream"), s.packIds())
    }
}
