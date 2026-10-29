package app.pisandbox

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.util.Log
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** The program + argv + environment to run on a PTY. */
data class LaunchSpec(val exe: String, val argv: Array<String>, val env: Array<String>, val cwd: String)

/**
 * Runs the Debian 12 arm64 userland natively on the phone's CPU under PRoot (ptrace-based fake
 * chroot / fake root). Per launch:
 *  1. installs the rootfs (first run) and the runtime overlay (first run + after app updates);
 *  2. writes /etc/resolv.conf from Android's current DNS servers;
 *  3. runs /usr/lib/pisandbox/prelaunch.sh as fake root, which generates the board identity files
 *     (cpuinfo, device-tree, /proc/version, uname) + a list of binds;
 *  4. starts `bash -l` as user "pi" (uid 1000, passwordless sudo) with those binds and a faked uname
 *     (-k: hostname "pisandbox", Pi-OS kernel release).
 *
 * v0.3 fix (phone showed pi@localhost and "Permission denied" on /proc/device-tree/model): v0.2 ran
 * prelaunch with Android's environment (PATH=/system/bin), so inside the guest no command was found,
 * nothing was generated, and the session started with no binds and no -k. Prelaunch now runs under
 * `env -i PATH=<guest PATH>`; if it still fails, [fallbackIdentity] generates the same files from
 * Kotlin, and -k is always passed. Every start logs a self-check of what was applied (logcat tag
 * "PiSandbox", and /etc/pisandbox/generated/overlays.txt, shown by `pisandbox-selfcheck`).
 */
class Sandbox(private val ctx: Context) {
    private val lib = ctx.applicationInfo.nativeLibraryDir
    private val proot = File(lib, "libproot.so")
    private val tmp = File(ctx.filesDir, "proot-tmp")

    fun prepare(progress: (String) -> Unit): LaunchSpec {
        check(proot.exists()) { "libproot.so missing from APK (run scripts/fetch-assets.sh before building)" }
        val installer = RootfsInstaller(ctx)
        installer.install(progress)
        installer.applyRuntime(progress)
        val rootfs = installer.rootfs.absolutePath
        tmp.mkdirs()
        writeResolvConf(installer.rootfs)

        progress("Preparing sandbox…")
        val gen = File(rootfs, "etc/pisandbox/generated")
        val report = ArrayList<String>()
        val pre = ProcessBuilder(listOf(proot.path) + baseArgs(rootfs, "-0") + listOf("-w", "/",
                "/usr/bin/env", "-i", "PATH=$GUEST_PATH", "LANG=C.UTF-8", "/bin/bash", "/usr/lib/pisandbox/prelaunch.sh"))
            .redirectErrorStream(true).apply { environment().clear(); environment().putAll(hostEnv()) }.start()
        val out = pre.inputStream.bufferedReader().readText().trim()
        val finished = pre.waitFor(60, TimeUnit.SECONDS)
        if (!finished) pre.destroy()
        val rc = if (finished) pre.exitValue() else -1
        Log.i(TAG, "prelaunch rc=$rc: $out")
        report += "prelaunch: rc=$rc ${out.lines().lastOrNull().orEmpty()}"
        if (rc != 0 || !File(gen, "binds").isFile || !File(gen, "device-tree/model").isFile) {
            report += "prelaunch output incomplete -> Kotlin fallback identity generated"
            Log.w(TAG, "prelaunch failed/incomplete; using Kotlin fallback")
            runCatching { fallbackIdentity(gen) }.onFailure { Log.e(TAG, "fallback failed", it); report += "fallback failed: $it" }
        }

        // Binds: whole directories (device-tree) or single files, guest-relative sources. Sources must
        // exist, else PRoot silently skips/rejects them -- check and report each one.
        val extra = ArrayList<String>()
        File(gen, "binds").takeIf { it.isFile }?.readLines()?.forEach { line ->
            val i = line.indexOf(':'); if (i <= 0) return@forEach
            val src = File(rootfs + line.substring(0, i)); val dest = line.substring(i + 1)
            if (src.exists()) {
                extra += "-b"; extra += src.path + ":" + dest
                report += "overlay applied: $dest <- ${line.substring(0, i)}${if (src.isDirectory) "/ (dir, ${src.list()?.size ?: 0} entries)" else " (${src.length()} bytes)"}"
            } else report += "overlay MISSING: $dest (source ${line.substring(0, i)} not generated)"
        }
        val uname = File(gen, "uname").takeIf { it.isFile }?.readText()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: fallbackUname().also { report += "uname: prelaunch value missing, using Kotlin fallback" }
        extra += "-k"; extra += uname
        report += "uname -k: $uname"
        selfCheck(rootfs, report)
        runCatching { File(gen, "overlays.txt").writeText(report.joinToString("\n", postfix = "\n")) }

        val argv = listOf("proot") + baseArgs(rootfs, "-i", "1000:1000") + extra + listOf(
            "-w", "/home/pi",
            "/usr/bin/env", "-i",
            "HOME=/home/pi", "USER=pi", "LOGNAME=pi", "SHELL=/bin/bash",
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
            "TZ=${TimeZone.getDefault().id}",
            "PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games",
            "GPIOZERO_PIN_FACTORY=pisandbox",
            // silences sudo's "unable to send audit message" (rootfs/shim/noaudit.c)
            "LD_PRELOAD=/usr/lib/aarch64-linux-gnu/libpisandbox-noaudit.so",
            "/bin/bash", "-l"
        )
        val env = hostEnv().map { "${it.key}=${it.value}" } + listOf("HOME=${ctx.filesDir.path}", "TERM=xterm-256color")
        return LaunchSpec(proot.path, argv.toTypedArray(), env.toTypedArray(), ctx.filesDir.path)
    }

    /** Startup self-check: logs which overlays applied and what Android itself lets the app read. */
    private fun selfCheck(rootfs: String, report: MutableList<String>) {
        fun readable(p: String) = runCatching { File(p).inputStream().use { it.read() }; true }.getOrDefault(false)
        report += "host: /proc/cpuinfo readable=${readable("/proc/cpuinfo")}, /proc/device-tree/model readable=${readable("/proc/device-tree/model")}, " +
            "/proc/stat readable=${readable("/proc/stat")}, /proc/loadavg readable=${readable("/proc/loadavg")}"
        val model = runCatching { File(rootfs, "etc/pisandbox/generated/device-tree/model").readText().trimEnd('\u0000') }.getOrDefault("?")
        val rev = runCatching { File(rootfs, "etc/pisandbox/generated/cpuinfo").readLines().firstOrNull { it.startsWith("Revision") } }.getOrNull()
        report += "generated: model='$model' cpuinfo '${rev ?: "no Revision line"}'"
        for (r in report) Log.i(TAG, "self-check: $r")
    }

    /** Minimal Kotlin port of prelaunch.sh's identity generation (used only if prelaunch fails). */
    private fun fallbackIdentity(gen: File) {
        val conf = HashMap<String, String>()
        runCatching { File(gen.parentFile, "board.conf").readLines() }.getOrDefault(emptyList()).forEach { l ->
            Regex("""^([A-Z_]+)=["']?(.*?)["']?$""").find(l.trim())?.let { conf[it.groupValues[1]] = it.groupValues[2] }
        }
        val model = conf["MODEL"] ?: "Raspberry Pi 4 Model B Rev 1.5"
        val revision = conf["REVISION"] ?: "c03115"
        val serial = runCatching { File(gen.parentFile, "serial").readText().trim() }.getOrNull()?.takeIf { it.length == 16 } ?: "10000000c0ffee42"
        gen.deleteRecursively()
        val dt = File(gen, "device-tree/system").apply { mkdirs() }.parentFile!!
        fun nul(vararg s: String) = s.joinToString("") { "$it\u0000" }.toByteArray()
        File(dt, "model").writeBytes(nul(model))
        File(dt, "compatible").writeBytes(nul(*(conf["COMPATIBLE"] ?: "raspberrypi,4-model-b brcm,bcm2711").split(' ').filter { it.isNotEmpty() }.toTypedArray()))
        File(dt, "serial-number").writeBytes(nul(serial))
        File(dt, "name").writeBytes(nul(""))
        fun be32(hex: String) = java.nio.ByteBuffer.allocate(4).putInt(hex.toLong(16).toInt()).array()
        File(dt, "system/linux,revision").writeBytes(be32(revision))
        File(dt, "system/linux,serial").writeBytes(be32(serial.substring(0, 8)) + be32(serial.substring(8, 16)))
        val cpu = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
            .filterNot { Regex("^(Hardware|Revision|Serial|Model)\\s*:").containsMatchIn(it) }.joinToString("\n").trimEnd()
        File(gen, "cpuinfo").writeText("$cpu\n\n" + (conf["HARDWARE"]?.let { "Hardware\t: $it\n" } ?: "") +
            "Revision\t: $revision\nSerial\t\t: $serial\nModel\t\t: $model\n")
        val g = "/etc/pisandbox/generated"
        File(gen, "binds").writeText("$g/cpuinfo:/proc/cpuinfo\n$g/device-tree:/proc/device-tree\n$g/device-tree:/sys/firmware/devicetree/base\n")
        File(gen, "uname").writeText(fallbackUname() + "\n")
    }

    private fun fallbackUname(): String {
        val u = Os.uname()
        val base = Regex("^\\d+\\.\\d+(\\.\\d+)?").find(u.release)?.value ?: u.release
        return "\\Linux\\pisandbox\\$base+rpt-rpi-v8\\#1 SMP PREEMPT PiSandbox (host kernel ${u.release})\\${u.machine}\\(none)\\-1\\"
    }

    /** Same flags as scripts/lib.sh host_proot (minus -q qemu), so the host test matches the phone. */
    private fun baseArgs(rootfs: String, vararg id: String) = listOf(
        "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", *id,
        "-r", rootfs,
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-b", "/dev/urandom:/dev/random",
        "-b", "/proc/self/fd:/dev/fd",
        "-b", "/proc/self/fd/0:/dev/stdin", "-b", "/proc/self/fd/1:/dev/stdout", "-b", "/proc/self/fd/2:/dev/stderr",
        "-b", "$rootfs/tmp:/dev/shm",
    )

    private fun hostEnv() = mapOf(
        "LD_LIBRARY_PATH" to lib,
        "PROOT_LOADER" to "$lib/libproot-loader.so",
        "PROOT_LOADER_32" to "$lib/libproot-loader32.so",
        "PROOT_TMP_DIR" to tmp.path,
        "PATH" to "/system/bin",
    )

    private fun writeResolvConf(rootfs: File) {
        val dns = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrNull().orEmpty()
        val all = (dns + listOf("1.1.1.1", "8.8.8.8")).distinct().take(3) // glibc uses at most 3
        File(rootfs, "etc/resolv.conf").apply { delete(); writeText(all.joinToString("") { "nameserver $it\n" }) }
    }

    companion object {
        const val TAG = "PiSandbox"
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }
}
