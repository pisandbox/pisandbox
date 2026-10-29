package app.pisandbox

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 1. First run: unpacks the bundled Debian 12 (bookworm) arm64 rootfs (assets/rootfs/…tar.xz,
 *    built by scripts/build-rootfs.sh) into filesDir/rootfs. Pure Java extraction
 *    (commons-compress + XZ for Java). Extracts into rootfs.partial and renames at the end, so an
 *    interrupted first run just restarts.
 * 2. First run and after every app update: applies the small *runtime overlay*
 *    (assets/runtime/pisandbox-runtime.tar, scripts/build-runtime.sh: prelaunch, GPIO shims,
 *    gpiozero, tools, examples) on top, so upgrades reach existing installs without touching
 *    /home, apt state or anything the user installed.
 */
class RootfsInstaller(
    private val ctx: Context,
    /** Free bytes where the rootfs goes; injectable for tests. */
    private val freeBytes: () -> Long = { allocatableBytes(ctx) },
) {
    val rootfs: File get() = File(ctx.filesDir, "rootfs")
    private val marker get() = File(rootfs, ".pisandbox-installed")
    private val stampFile get() = File(rootfs, "etc/pisandbox/runtime-stamp")

    fun isInstalled() = marker.exists()

    fun install(progress: (String) -> Unit) {
        if (isInstalled()) return
        val work = File(ctx.filesDir, "rootfs.partial")
        work.deleteRecursively(); rootfs.deleteRecursively()
        val free = freeBytes()
        if (free in 0 until NEEDED_BYTES) throw NotEnoughStorageException(NEEDED_BYTES, free)
        reserveSpace(ctx, NEEDED_BYTES)   // lets Android clear other apps' caches now if that is where the space is
        work.mkdirs()
        try { unpack(work, progress) } catch (e: Exception) {
            if (isOutOfSpace(e)) { work.deleteRecursively(); throw NotEnoughStorageException(NEEDED_BYTES, freeBytes(), e) }
            throw e
        }
    }

    private fun unpack(work: File, progress: (String) -> Unit) {
        val total = runCatching { ctx.assets.openFd(ASSET).use { it.length } }.getOrDefault(-1L)
        var read = 0L; var lastPct = -1
        val counting = object : FilterInputStream(ctx.assets.open(ASSET).buffered(1 shl 16)) {
            override fun read(): Int = super.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        val n = extract(XZInputStream(counting as InputStream), work, overwrite = false) { count ->
            if (total > 0) {
                val pct = (read * 100 / total).toInt()
                if (pct != lastPct) { lastPct = pct; progress("First run: unpacking Debian 12… $pct%") }
            } else if (count % 500 == 0) progress("First run: unpacking Debian 12… $count files")
        }
        File(work, "tmp").apply { mkdirs(); setMode(this, 0b1_111_111_111) }
        File(work, ".pisandbox-installed").writeText("${BuildConfig.VERSION_NAME} $n\n")
        if (!work.renameTo(rootfs)) error("could not move rootfs into place")
        progress("Rootfs ready ($n entries)")
    }

    /** Re-applies the runtime overlay when the app was installed/updated since the last apply. Returns true if applied. */
    fun applyRuntime(progress: (String) -> Unit): Boolean {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val stamp = "${BuildConfig.VERSION_NAME} ${pi.lastUpdateTime}"
        if (runCatching { stampFile.readText().trim() }.getOrNull() == stamp) return false
        progress("Installing PiSandbox ${BuildConfig.VERSION_NAME} tools…")
        val n = try {
            ctx.assets.open(RUNTIME_ASSET).buffered(1 shl 16).use { extract(it, rootfs, overwrite = true) {} }
        } catch (e: Exception) {
            if (isOutOfSpace(e)) throw NotEnoughStorageException(RUNTIME_NEEDED_BYTES, freeBytes(), e)
            throw e
        }
        stampFile.parentFile?.mkdirs(); stampFile.writeText("$stamp\n")
        android.util.Log.i(Sandbox.TAG, "runtime overlay applied: $n entries ($stamp)")
        return true
    }

    /** Untars [input] into [dest] (modes incl. setuid; hard links copied; device nodes skipped). */
    private fun extract(input: InputStream, dest: File, overwrite: Boolean, onEntry: (Int) -> Unit): Int {
        var n = 0
        val dirModes = ArrayList<Pair<File, Int>>()
        val base = dest.canonicalPath + File.separator
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                val out = File(dest, name)
                if (!(out.canonicalPath + File.separator).startsWith(base) && !e.isSymbolicLink) continue // traversal guard
                out.parentFile?.mkdirs()
                if (overwrite && !e.isDirectory) out.delete() // replace read-only files / old symlinks
                when {
                    e.isDirectory -> { if (!out.isDirectory) { out.mkdirs(); dirModes += out to e.mode } }
                    e.isSymbolicLink -> { out.delete(); Os.symlink(e.linkName, out.path) }
                    // Hard links: copied (link() is often denied to apps; proot's link2symlink handles new ones)
                    e.isLink -> File(dest, e.linkName.removePrefix("./")).copyTo(out, overwrite = true)
                    e.isFile -> out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                    else -> continue // device nodes / fifos: proot binds the real /dev
                }
                if (!e.isDirectory && !e.isSymbolicLink) setMode(out, e.mode)
                onEntry(++n)
            }
        }
        // Directory modes last (deepest first), so read-only dirs don't block their own children.
        for ((d, m) in dirModes.asReversed()) setMode(d, m or 0b111_000_000) // owner rwx: we are the owner
        return n
    }

    /** chmod incl. setuid/setgid/sticky (sudo needs setuid); fall back to plain rwx bits. */
    private fun setMode(f: File, mode: Int) {
        try { Os.chmod(f.path, mode and 0xfff) } catch (_: Exception) {
            runCatching { Os.chmod(f.path, mode and 0x1ff) }
        }
    }

    companion object {
        /** Unpacked Debian 12 is ~440 MB; ask for headroom so apt/pip still work afterwards. */
        const val NEEDED_BYTES = 600L * 1024 * 1024
        const val RUNTIME_NEEDED_BYTES = 10L * 1024 * 1024

        /** True if [e] (or a cause) is "No space left on device" (ENOSPC) or a quota error. */
        fun isOutOfSpace(e: Throwable?): Boolean {
            var t = e; var depth = 0
            while (t != null && depth++ < 8) {
                if (t is android.system.ErrnoException && (t.errno == android.system.OsConstants.ENOSPC || t.errno == android.system.OsConstants.EDQUOT)) return true
                val m = t.message.orEmpty()
                if ("ENOSPC" in m || "No space left" in m || "EDQUOT" in m || "quota exceeded" in m.lowercase()) return true
                t = t.cause
            }
            return false
        }
        const val ASSET = "rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz"
        const val RUNTIME_ASSET = "runtime/pisandbox-runtime.tar"
    }
}

/** Thrown when there is not enough free storage to set up Linux. */
class NotEnoughStorageException(val neededBytes: Long, val freeBytes: Long, cause: Throwable? = null) :
    java.io.IOException("Not enough free storage: need about ${neededBytes / (1024 * 1024)} MB, ${freeBytes / (1024 * 1024)} MB free", cause)

/**
 * Space we can use in filesDir: StorageManager.getAllocatableBytes also counts cached data Android will clear for us
 * (API 26+), so a phone that is "full" of other apps' caches can still install. Falls back to File.usableSpace.
 */
internal fun allocatableBytes(ctx: Context): Long = runCatching {
    val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
    maxOf(sm.getAllocatableBytes(sm.getUuidForPath(ctx.filesDir)), ctx.filesDir.usableSpace)
}.getOrDefault(ctx.filesDir.usableSpace)

/** Best effort: StorageManager.allocateBytes frees cached data so [bytes] can really be written (no-op if already free). */
internal fun reserveSpace(ctx: Context, bytes: Long) {
    runCatching {
        if (ctx.filesDir.usableSpace >= bytes) return
        val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
        sm.allocateBytes(sm.getUuidForPath(ctx.filesDir), bytes)
    }
}
