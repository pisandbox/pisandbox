package app.pisandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * Listens on the abstract Unix socket "@app.pisandbox.ctl" (no file needed inside the rootfs; PRoot
 * leaves abstract addresses alone). Only processes running as this app's own uid -- i.e. the
 * Linux sandbox -- are accepted; other apps are rejected via SO_PEERCRED.
 */
class ControlServer(private val core: GpioCore) {
    @Volatile private var server: LocalServerSocket? = null

    fun start() {
        if (server != null) return
        val srv = try { LocalServerSocket(NAME) } catch (e: Exception) { Log.e(TAG, "control socket unavailable", e); return }
        server = srv
        thread(name = "ctl-accept", isDaemon = true) {
            while (server === srv) {
                val c: LocalSocket = try { srv.accept() } catch (_: Exception) { break }
                val uid = runCatching { c.peerCredentials.uid }.getOrDefault(-1)
                if (uid != Process.myUid()) { Log.w(TAG, "rejected control client uid=$uid"); runCatching { c.close() }; continue }
                thread(name = "ctl-client", isDaemon = true) { c.use { core.serve(it.inputStream, it.outputStream) } }
            }
        }
    }

    companion object { const val NAME = "app.pisandbox.ctl"; const val TAG = "PiSandbox" }
}

/** Process-wide GPIO state + control socket: survives activity re-creation (rotation etc.). */
object Gpio {
    val core = GpioCore()
    private val server = ControlServer(core)
    fun ensureStarted() = server.start()
}
