package dev.koggles.presets

import java.io.File
import java.io.IOException

/** File-system seam for [TextureInstaller], so tests can fail individual steps. */
interface InstallerFs {
    /** Atomic rename; replaces [to] if it is a file. Returns false on failure. */
    fun rename(from: File, to: File): Boolean
    fun writeText(f: File, s: String)
}

object RealInstallerFs : InstallerFs {
    override fun rename(from: File, to: File): Boolean = from.renameTo(to)
    override fun writeText(f: File, s: String) = f.writeText(s)
}

/**
 * Extracts the bundled textures (`assets/textures/`, flat) into
 * `filesDir/bundled-textures/v<versionKey>/` following spec §5.1. [versionKey] is
 * `PackageInfo.lastUpdateTime` (D2). Not thread-safe; run once, off the main thread.
 */
class TextureInstaller(
    private val src: AssetSource,
    filesDir: File,
    private val versionKey: Long,
    private val fs: InstallerFs = RealInstallerFs,
) {
    private val base = File(filesDir, DIR)
    private val pointer = File(base, "current")
    private val pointerTmp = File(base, "current.tmp")

    /**
     * Returns the active version directory: `v<versionKey>` on success, else the previously
     * active one. With no previous version and a failure it returns a directory that does not
     * exist, which callers treat as "no bundled textures".
     */
    fun installIfNeeded(): File = synchronized(INSTALL_LOCK) { installLocked() }

    private fun installLocked(): File {
        val target = "v$versionKey"
        val finalDir = File(base, target)
        val previous = activeDir()
        if (previous == finalDir) {
            cleanup(keep = target) // finishes a cleanup an earlier run may not have reached
            return finalDir
        }
        return try {
            install(target, finalDir)
            cleanup(keep = target)
            finalDir
        } catch (e: IOException) {
            previous ?: File(base, MISSING)
        }
    }

    /** The directory named by a readable, well-formed pointer, if it exists. */
    private fun activeDir(): File? {
        val name = try {
            pointer.takeIf { it.isFile }?.readText()?.trim()
        } catch (e: IOException) {
            null
        } ?: return null
        if (!VERSION_NAME.matches(name)) return null
        return File(base, name).takeIf { it.isDirectory }
    }

    private fun install(target: String, finalDir: File) {
        if (!base.isDirectory && !base.mkdirs()) throw IOException("cannot create $base")
        // A complete v<key> exists only via the rename below, so an earlier run that failed to
        // publish can reuse it; never rename onto it.
        if (!finalDir.isDirectory) {
            val tmp = File(base, "$target.tmp")
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
            extractInto(tmp)
            if (!fs.rename(tmp, finalDir)) throw IOException("rename $tmp -> $finalDir failed")
        }
        fs.writeText(pointerTmp, target)
        if (!fs.rename(pointerTmp, pointer)) throw IOException("publish $target failed")
    }

    private fun extractInto(dir: File) {
        for (name in src.list(ASSET_DIR)) {
            if (extensionOf(name) !in TEXTURE_EXT) continue
            src.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** Deletes every other `v*` and `*.tmp` entry. Call only after the pointer names [keep]. */
    private fun cleanup(keep: String) {
        base.listFiles()?.forEach { f ->
            val stale = f.name != keep && (VERSION_NAME.matches(f.name) || f.name.endsWith(".tmp"))
            if (stale) f.deleteRecursively()
        }
    }

    companion object {
        const val DIR = "bundled-textures"
        const val ASSET_DIR = "textures"
        private const val MISSING = "none"
        private val VERSION_NAME = Regex("v-?\\d+")
    }
}

/** One install at a time per process: installs share staging and pointer files. */
private val INSTALL_LOCK = Any()
