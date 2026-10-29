package app.pisandbox

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * App-wide crash safety (no network, no third-party SDKs): an uncaught exception on any thread is written to
 * filesDir/last-crash.txt and a friendly [CrashActivity] (separate ":crash" process) shows it with
 * "Copy details" and "Restart". Nothing is ever sent anywhere; the user decides whether to share the text.
 * Debug builds also turn on StrictMode (logs only).
 */
class PiSandboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isCrashProcess()) return
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedRegistrationObjects()
                .detectActivityLeaks().detectFileUriExposure().penaltyLog().build())
        }
        CrashReport.install(this)
    }

    private fun isCrashProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().trimEnd('\u0000').endsWith(":crash")
    }.getOrDefault(false)
}

object CrashReport {
    private const val FILE = "last-crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val text = describe(app, e, "Uncaught exception on thread \"${thread.name}\"")
                runCatching { File(app.filesDir, FILE).writeText(text) }
                Log.e(Sandbox.TAG, text)
                app.startActivity(Intent(app, CrashActivity::class.java).putExtra(CrashActivity.EXTRA, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                Process.killProcess(Process.myPid()); exitProcess(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, e)
            }
        }
    }

    /** Plain-text report: app/device versions + stack trace. No personal data, never sent anywhere. */
    fun describe(ctx: Context, e: Throwable?, what: String): String {
        val sw = StringWriter(); e?.printStackTrace(PrintWriter(sw))
        val free = runCatching { allocatableBytes(ctx) / (1024 * 1024) }.getOrDefault(-1)
        return buildString {
            append("PiSandbox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""})\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}\n")
            append("Free storage: $free MB\n")
            append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}\n")
            append(what).append('\n')
            if (e != null) append(sw.toString())
        }
    }
}

/** Friendly "something went wrong" screen, shown in its own process after a crash. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val details = intent.getStringExtra(EXTRA)
            ?: runCatching { File(filesDir, "last-crash.txt").readText() }.getOrDefault("No details were saved.")
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(px(20), px(28), px(20), px(20)); fitsSystemWindows = true
        }
        root.addView(TextView(this).apply { text = "PiSandbox stopped unexpectedly"; textSize = 22f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply {
            text = "Sorry about that. Your Linux files are safe. Tap Restart to try again.\n\n" +
                "If it keeps happening, tap Copy details and paste them into your tester feedback. Nothing is sent automatically."
            textSize = 15f; setTextColor(Ui.TEXT2); setLineSpacing(0f, 1.2f); setPadding(0, px(12), 0, px(12))
        })
        val log = TextView(this).apply {
            text = details; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Ui.TEXT); setTextIsSelectable(true)
            setPadding(px(12), px(12), px(12), px(12)); background = Ui.rounded(Ui.TERM_BG, px(12).toFloat(), Ui.OUTLINE, px(1))
        }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, px(12), 0, 0) }
        row.addView(Button(this).apply { text = "Copy details"; setOnClickListener { copy(this@CrashActivity, details) } })
        row.addView(Button(this).apply {
            text = "Restart"
            setOnClickListener {
                startActivity(Intent(this@CrashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        })
        root.addView(row)
        setContentView(root)
    }

    companion object {
        const val EXTRA = "details"
        fun copy(ctx: Context, text: String) {
            (ctx.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PiSandbox details", text))
            Toast.makeText(ctx, "Details copied", Toast.LENGTH_SHORT).show()
        }
    }
}
