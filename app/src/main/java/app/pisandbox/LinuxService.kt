package app.pisandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.ref.WeakReference

/**
 * Process-wide Linux state. The shell outlives the Activity (rotation, dark-mode or font-size changes,
 * the system recreating a backgrounded screen), so a running program is never killed by a UI event.
 */
object Linux {
    @Volatile var session: TerminalSession? = null
    var startedAt = 0L
    /** The visible MainActivity (progress / results of a running prepare() go here). */
    var host: WeakReference<MainActivity>? = null
    /** prepare() is running on a background thread. */
    @Volatile var preparing = false
    /** We asked the shell to stop (Power off / Restart), so a quick exit is not an error. */
    @Volatile var stopRequested = false
    /** prepare() finished while no activity was attached: delivered on the next onCreate. */
    var pending: Result<LaunchSpec>? = null

    val isRunning get() = session?.isRunning == true

    /** Application context (no Activity leak) so a detached session can still clear its notification. */
    @Volatile var appContext: Context? = null

    fun powerOff(ctx: Context) {
        stopRequested = true
        session?.finishIfRunning()
        LinuxService.stop(ctx)
    }

    /** Client used while no activity is attached (keeps the session from holding a destroyed Activity). */
    val detachedClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) { appContext?.let { LinuxService.stop(it) } }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: Sandbox.TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: Sandbox.TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: Sandbox.TAG, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: Sandbox.TAG, "error", e) }
    }
}

/**
 * Keeps the app process alive while Linux runs in the background (a long python script, a web server),
 * with an ongoing "Linux is running" notification that has a Power off action. Purely local: no network.
 * Swiping PiSandbox away from Recents powers Linux off.
 */
class LinuxService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_POWER_OFF || !Linux.isRunning) {
            Linux.session?.finishIfRunning()
            @Suppress("DEPRECATION") stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, notification()) }.onFailure { Log.w(Sandbox.TAG, "foreground service refused", it); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Linux.session?.finishIfRunning(); stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Linux is running so your programs keep going in the background"; setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 1, Intent(this, LinuxService::class.java).setAction(ACTION_POWER_OFF), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pisandbox)
            .setContentTitle("Linux is running")
            .setContentText("Tap to open PiSandbox")
            .setContentIntent(open)
            .setOngoing(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Power off", off).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "linux"
        private const val NOTIFICATION_ID = 1
        const val ACTION_POWER_OFF = "app.pisandbox.POWER_OFF"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, LinuxService::class.java)) }
                .onFailure { Log.w(Sandbox.TAG, "could not start LinuxService", it) }
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, LinuxService::class.java)) } }
    }
}
