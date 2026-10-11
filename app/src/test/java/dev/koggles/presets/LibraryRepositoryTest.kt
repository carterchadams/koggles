package dev.koggles.presets

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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

    @Test fun userRootListingThrows_recordsErrorAndStopsIndexing() {
        val broken = object : DocTree by user {
            override fun children(node: DocNode): List<DocNode> =
                if (node.id.isEmpty()) throw IOException("provider unavailable") else user.children(node)
        }
        val s = await(repo(userTree = { broken }), "settled") { it.bundledReady && !it.userIndexing && it.errors.isNotEmpty() }
        assertEquals(setOf("bundled:Cream"), s.packIds())
        assertTrue(s.errors.any { "provider unavailable" in it })
    }

    @Test fun mirrorOperations_neverOverlap_acrossRefreshAndLazyRebuild() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxActive = java.util.concurrent.atomic.AtomicInteger(0)
        val slowMirror = TextureMirror(tmp.newFolder("slow-cache"), { _, f ->
            maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(150)
            f.writeBytes(byteArrayOf(1))
            active.decrementAndGet()
        })
        val multi = FakeDocTree(listOf("Mine/x.milk", "Mine/a.png", "Mine/b.png", "Mine/c.png"))
        val r = LibraryRepository(scope, { bundled }, { bundledTex }, { multi }, slowMirror, PresetIndexer(Dispatchers.IO), Dispatchers.IO)
        await(r, "mirrored") { "user:Mine" in it.packTextureDirs && !it.userIndexing }
        r.state.value.packTextureDirs.getValue("user:Mine").deleteRecursively()
        r.refresh()
        val lazy = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) { r.texturePathsFor("user:Mine") }
        lazy.await()
        await(r, "re-indexed") { !it.userIndexing }
        Thread.sleep(500)
        assertEquals("mirror applies must be serialized", 1, maxActive.get())
    }

    @Test fun copiesUseThePacksOwnTree() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<DocTree>())
        val m = TextureMirror(tmp.newFolder("bound-cache"), copy = { _, _ -> error("unbound copy used") }, copyFrom = { tree, _, f ->
            seen += tree
            f.writeBytes(byteArrayOf(1))
        })
        val r = LibraryRepository(scope, { bundled }, { bundledTex }, { user }, m, PresetIndexer(Dispatchers.IO), Dispatchers.IO)
        await(r, "mirrored") { "user:Mine" in it.packTextureDirs && !it.userIndexing }
        assertTrue(seen.isNotEmpty() && seen.all { it === user })
    }

    @Test fun lazyRebuild_afterRefreshStarted_doesNotApplyStalePack() = runBlocking {
        val oldTreeCopies = java.util.concurrent.atomic.AtomicInteger(0)
        val release = CountDownLatch(1)
        val replacement = object : DocTree by FakeDocTree(listOf("Mine/y.milk", "Mine/other.png")) {
            override fun children(node: DocNode): List<DocNode> {
                release.await(10, TimeUnit.SECONDS)
                return FakeDocTree(listOf("Mine/y.milk", "Mine/other.png")).children(node)
            }
        }
        var tree: DocTree = user
        val m = TextureMirror(tmp.newFolder("gen-cache"), copy = { _, _ -> error("unbound") }, copyFrom = { t, _, f ->
            if (t === user) oldTreeCopies.incrementAndGet()
            f.writeBytes(byteArrayOf(1))
        })
        val r = LibraryRepository(scope, { bundled }, { bundledTex }, { tree }, m, PresetIndexer(Dispatchers.IO), Dispatchers.IO)
        await(r, "mirrored") { "user:Mine" in it.packTextureDirs && !it.userIndexing }
        val copiesBefore = oldTreeCopies.get()
        r.state.value.packTextureDirs.getValue("user:Mine").deleteRecursively()
        tree = replacement
        r.refresh()
        await(r, "refresh started") { it.userIndexing }
        r.texturePathsFor("user:Mine") // stale request for the old pack
        assertEquals("old tree must not be re-mirrored after a refresh began", copiesBefore, oldTreeCopies.get())
        release.countDown()
    }

    @Test fun grantProbe_runsOffTheCallerScopeThread() {
        val probeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val single = java.util.concurrent.Executors.newSingleThreadExecutor()
        val mainLike = CoroutineScope(SupervisorJob() + single.asCoroutineDispatcher())
        val scopeThread = single.submit<Thread> { Thread.currentThread() }.get()
        val r = LibraryRepository(mainLike, { bundled }, { bundledTex }, { probeThread.set(Thread.currentThread()); null },
            mirror, PresetIndexer(Dispatchers.IO), Dispatchers.IO)
        await(r, "ready") { it.bundledReady && !it.userIndexing }
        assertTrue("userTree() must not run on the scope's (main) thread", probeThread.get() !== scopeThread)
        mainLike.cancel(); single.shutdown()
    }

    @Test fun refreshWithFailingRoot_dropsPreviousUserPacks() {
        var failing = false
        val flaky = object : DocTree by user {
            override fun children(node: DocNode): List<DocNode> =
                if (failing && node.id.isEmpty()) throw IOException("folder deleted") else user.children(node)
        }
        val r = repo(userTree = { flaky })
        await(r, "user merged") { "user:Mine" in it.packIds() && !it.userIndexing }
        failing = true
        r.refresh()
        val s = await(r, "dropped") { "user:Mine" !in it.packIds() && !it.userIndexing }
        assertEquals(setOf("bundled:Cream"), s.packIds())
        assertTrue(s.packTextureDirs.isEmpty())
        assertTrue(s.errors.any { "folder deleted" in it })
    }

    @Test fun missingSingleTexture_isRetriedBeforeReturn() = runBlocking {
        val r = repo()
        val s = await(r, "mirrored") { "user:Mine" in it.packTextureDirs && !it.userIndexing }
        val dir = s.packTextureDirs.getValue("user:Mine")
        File(dir, "k.png").delete() // directory survives, one texture is gone
        r.texturePathsFor("user:Mine")
        assertTrue(File(dir, "k.png").isFile)
    }

    @Test fun transientCopyFailure_recoversOnNextLoad() = runBlocking {
        var fail = true
        val m = TextureMirror(tmp.newFolder("flaky-cache"), { _, f -> if (fail) throw IOException("busy"); f.writeBytes(byteArrayOf(1)) })
        val r = LibraryRepository(scope, { bundled }, { bundledTex }, { user }, m, PresetIndexer(Dispatchers.IO), Dispatchers.IO)
        val s = await(r, "indexed") { !it.userIndexing && "user:Mine" in it.packIds() }
        fail = false
        val paths = r.texturePathsFor("user:Mine")
        val dir = File(paths.first())
        assertTrue(File(dir, "k.png").isFile)
    }
}
