package app.pisandbox

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * PiSandbox main screen (v0.4): dark Material-style app with a header (icon, title, status pill,
 * settings), and a bottom navigation bar with six tabs:
 *   Terminal  - Linux terminal card, a one-row extra-keys bar, Power / Restart / Lessons buttons,
 *               and optional "Game mode" handheld controls (D-pad, A = Enter, B = Ctrl-C, START/SELECT)
 *   Files     - browse /home/pi, view text files, run .py files
 *   GPIO      - the virtual 40-pin header, full screen, with a Stop (Ctrl-C) button
 *   Browser   - WebView with back/forward/reload, address bar, bookmarks (also opened by `browse`)
 *   Lessons   - the ~/examples missions with a Run button
 *   Settings  - LCD tint, Game mode, font size, About & open-source licenses
 *
 * v0.5: sand startup animation ([SandView]), Linux survives Activity recreation ([Linux] holder +
 * [LinuxService]), friendly startup-error panel, crash screen ([CrashActivity]).
 */
class MainActivity : Activity(), TerminalSessionClient, TerminalViewClient {
    enum class Tab(val label: String, val icon: IconDrawable.Kind) {
        TERMINAL("Terminal", IconDrawable.Kind.TERMINAL), FILES("Files", IconDrawable.Kind.FILES),
        GPIO("GPIO", IconDrawable.Kind.GPIO), BROWSER("Browser", IconDrawable.Kind.BROWSER),
        LESSONS("Lessons", IconDrawable.Kind.LESSONS), SETTINGS("Settings", IconDrawable.Kind.SETTINGS)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("pisandbox", MODE_PRIVATE) }
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Number) = (v.toFloat() * dp).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    lateinit var term: TerminalView; private set
    private lateinit var statusPill: TextView
    private lateinit var statusDot: GradientDrawable
    private lateinit var subtitle: TextView
    private lateinit var header: View
    private lateinit var navBar: LinearLayout
    private lateinit var actionRow: View
    private lateinit var powerBtn: LinearLayout
    private lateinit var gameControls: View
    private lateinit var keysBar: View
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    lateinit var splash: SandView; private set
    private lateinit var errorPanel: View
    private lateinit var aboutPanel: View
    private lateinit var aboutList: View
    private lateinit var aboutText: TextView
    private lateinit var aboutTextScroll: ScrollView
    private lateinit var errorTitle: TextView
    private lateinit var errorMsg: TextView
    private var errorDetails = ""
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var gpioRunning: TextView
    private lateinit var filesList: ListView
    private lateinit var filesPath: TextView
    private lateinit var filesUp: View
    private lateinit var viewer: View
    private lateinit var viewerTitle: TextView
    private lateinit var viewerText: TextView
    private lateinit var viewerRun: View
    private lateinit var fontLabel: TextView
    private lateinit var lcdSwitch: Switch
    private lateinit var gameSwitch: Switch
    private val pages = HashMap<Tab, View>()
    private val navItems = HashMap<Tab, LinearLayout>()

    private var session: TerminalSession? = null
    var currentTab = Tab.TERMINAL; private set
    private var ctrlLatched = false
    private var altLatched = false
    var lcd = false; private set
    var gameMode = false; private set
    var fontSp = 12; private set
    private var keyboardShown = false
    private var introDone = false
    private var ready = false
    private var splashHiding = false
    private var filesDirCur: File? = null
    private var viewerFile: File? = null
    /** Button clicks + terminal typing ticks (Settings: Sounds). */
    lateinit var sounds: Sounds; private set
    private var visible = false
    private var tickPumping = false
    private val tickPump = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (sounds.ticker.poll(now)) sounds.tick()
            if (visible && sounds.ticker.active(now)) ui.postDelayed(this, sounds.ticker.nextDelay(now)) else tickPumping = false
        }
    }

    // ================================================================== lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lcd = prefs.getBoolean("lcd", false)
        gameMode = prefs.getBoolean("game", false)
        fontSp = prefs.getInt("fontSp", 12)
        sounds = Sounds(this, prefs)
        setContentView(buildUi())
        Linux.host = WeakReference(this); Linux.appContext = applicationContext
        Gpio.core.onBrowse = { url -> runOnUiThread { openBrowser(url) } }
        savedInstanceState?.let { st ->
            st.getString("tab")?.let { n -> Tab.values().firstOrNull { it.name == n } }?.let { selectTab(it) }
            st.getBundle("web")?.let { runCatching { web.restoreState(it) } }
        }
        val existing = Linux.session
        val pending = Linux.pending
        when {
            existing != null && existing.isRunning -> { noSplash(); reattach(existing) }   // recreated Activity: same shell
            pending != null -> { noSplash(); Linux.pending = null; onPrepared(pending) }
            Linux.preparing -> { setStatus(Status.STARTING); showSplash(firstRun = false) } // recreated mid-start
            savedInstanceState?.getBoolean("off") == true -> { noSplash(); setStatus(Status.STOPPED) } // user had powered off
            savedInstanceState != null -> { noSplash(); start() }                           // after process death
            testMode -> { noSplash(); setStatus(Status.RUNNING) }
            else -> { showSplash(firstRun = !RootfsInstaller(this).isInstalled()); start() }
        }
        if (!testMode) Gpio.ensureStarted()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", currentTab.name)
        out.putBoolean("off", !Linux.isRunning && !Linux.preparing && Linux.stopRequested)
        if (web.url != null) out.putBundle("web", Bundle().also { web.saveState(it) })
    }

    override fun onDestroy() {
        if (Linux.host?.get() === this) Linux.host = null
        val s = session
        if (isFinishing && !isChangingConfigurations) Linux.powerOff(this)
        else s?.updateTerminalSessionClient(Linux.detachedClient) // keep Linux running; the next Activity re-attaches
        Gpio.core.onBrowse = null
        splash.stop()
        ui.removeCallbacks(tickPump); sounds.release()
        super.onDestroy()
    }

    private fun reattach(s: TerminalSession) {
        session = s; s.updateTerminalSessionClient(this); term.attachSession(s); applyPalette()
        setStatus(Status.RUNNING); ready = true
    }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }

    /** Terminal printed something: typing ticks (only while the Terminal tab is on screen; output is never slowed). */
    @VisibleForTesting fun onTerminalOutput() {
        if (!sounds.typingOn || !visible || currentTab != Tab.TERMINAL) return
        sounds.ticker.onOutput(SystemClock.uptimeMillis())
        if (!tickPumping) { tickPumping = true; tickPump.run() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); updateChrome() }

    // ================================================================== building blocks
    private fun text(s: String, sp: Float, color: Int = Ui.TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun icon(kind: IconDrawable.Kind, color: Int, sizeDp: Int = 22) = ImageView(this).apply {
        setImageDrawable(IconDrawable(kind, color)); layoutParams = lp(px(sizeDp), px(sizeDp))
    }

    /** Wide rounded action button with an icon (Power Off / Restart / Lessons ...). */
    private fun action(kind: IconDrawable.Kind, label: String, fill: Int, fg: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER; background = Ui.rounded(fill, px(14).toFloat(), if (fill == Ui.SURFACE2) Ui.OUTLINE else null, px(1))
        setPadding(px(6), 0, px(6), 0); isClickable = true; isFocusable = false
        foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, Ui.rounded(Color.WHITE, px(14).toFloat()))
        addView(icon(kind, fg, 20), lp(px(20), px(20)).apply { marginEnd = px(6) })
        addView(text(label, 14f, fg).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; tag = "label" }, lp(WRAP, WRAP))
        tap { onClick() }
    }

    private fun iconButton(kind: IconDrawable.Kind, color: Int = Ui.TEXT, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(IconDrawable(kind, color)); setPadding(px(9), px(9), px(9), px(9)); isFocusable = false
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        tap { onClick() }
    }

    /** Click listener with our click sound (Android's own touch sound is switched off for the view: no double click). */
    private fun View.tap(f: (View) -> Unit) { isSoundEffectsEnabled = false; setOnClickListener { sounds.click(); f(it) } }

    private fun card(child: View, bg: Int = Ui.SURFACE) = FrameLayout(this).apply {
        background = Ui.rounded(bg, px(16).toFloat(), Ui.OUTLINE, px(1)); clipToOutline = true
        setPadding(px(1), px(1), px(1), px(1)); addView(child, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ================================================================== layout
    private fun buildUi(): View {
        val outer = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(buildHeader().also { header = it }, lp(MATCH, WRAP))
        root.addView(View(this).apply { setBackgroundColor(Ui.OUTLINE) }, lp(MATCH, px(1)))
        val content = FrameLayout(this)
        pages[Tab.TERMINAL] = buildTerminalPage(); pages[Tab.FILES] = buildFilesPage(); pages[Tab.GPIO] = buildGpioPage()
        pages[Tab.BROWSER] = buildBrowserPage(); pages[Tab.LESSONS] = buildLessonsPage(); pages[Tab.SETTINGS] = buildSettingsPage()
        for (t in Tab.values()) content.addView(pages[t], FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(content, lp(MATCH, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(Ui.OUTLINE) }, lp(MATCH, px(1)))
        root.addView(buildNav().also { navBar = it }, lp(MATCH, WRAP))
        outer.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        splash = buildSplash(); outer.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        outer.addView(buildAboutOverlay().also { aboutPanel = it }, FrameLayout.LayoutParams(MATCH, MATCH))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect(); root.getWindowVisibleDisplayFrame(r)
            val h = root.rootView.height
            val kb = h > 0 && h - r.bottom > h * 0.18
            if (kb != keyboardShown) { keyboardShown = kb; updateChrome() }
        }
        selectTab(Tab.TERMINAL)
        updateChrome()
        return outer
    }

    private fun appIcon(sizeDp: Int) = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0x26, 0x32, 0x38)) }
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) } }
        clipToOutline = true
        // the adaptive-icon foreground is 108 units with the artwork in ~28..80: scale so the chip fills the circle
        addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher_foreground); scaleX = 1.75f; scaleY = 1.75f },
            FrameLayout.LayoutParams(px(sizeDp), px(sizeDp)))
    }

    private fun buildHeader(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(8), px(10)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(appIcon(40), lp(px(40), px(40)).apply { marginEnd = px(12) })
        row.addView(text("PiSandbox", 24f, Ui.TEXT, true).apply { maxLines = 1 }, lp(0, WRAP, 1f))
        statusDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize(px(8), px(8)) }
        statusPill = text("Starting", 13f, Ui.GREEN).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(12), px(7), px(14), px(7)); compoundDrawablePadding = px(8)
            setCompoundDrawablesRelative(statusDot, null, null, null); maxLines = 1
        }
        row.addView(statusPill, lp(WRAP, WRAP))
        row.addView(iconButton(IconDrawable.Kind.SETTINGS) { selectTab(Tab.SETTINGS) }.apply { contentDescription = "Settings" }, lp(px(44), px(44)).apply { marginStart = px(4) })
        box.addView(row, lp(MATCH, WRAP))
        subtitle = text("Debian 12 • arm64", 13f, Ui.TEXT2).apply {
            background = Ui.rounded(Ui.SURFACE2, px(16).toFloat()); setPadding(px(12), px(6), px(12), px(6))
        }
        box.addView(subtitle, lp(WRAP, WRAP).apply { topMargin = px(8) })
        return box
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply { setPadding(px(4), px(6), px(4), px(6)); setBackgroundColor(Ui.BG) }
        for (t in Tab.values()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6))
                isClickable = true; contentDescription = t.label
                addView(icon(t.icon, Ui.TEXT2, 22), lp(px(22), px(22)))
                addView(text(t.label, 11f, Ui.TEXT2).apply { maxLines = 1; gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = px(4) })
                tap { selectTab(t) }
            }
            navItems[t] = item
            bar.addView(item, lp(0, WRAP, 1f).apply { marginStart = px(2); marginEnd = px(2) })
        }
        return bar
    }

    fun selectTab(t: Tab) {
        currentTab = t
        for ((k, v) in pages) v.visibility = if (k == t) View.VISIBLE else View.GONE
        for ((k, item) in navItems) {
            val on = k == t
            item.background = if (on) Ui.rounded(Ui.ACCENT, px(14).toFloat()) else null
            ((item.getChildAt(0) as ImageView).drawable as IconDrawable).color = if (on) Color.WHITE else Ui.TEXT2
            (item.getChildAt(1) as TextView).setTextColor(if (on) Color.WHITE else Ui.TEXT2)
        }
        when (t) {
            Tab.FILES -> showDir(filesDirCur ?: homeDir())
            Tab.TERMINAL -> term.requestFocus()
            Tab.BROWSER -> if (web.url == null) web.loadDataWithBaseURL(null, START_PAGE, "text/html", "utf-8", null)
            else -> {}
        }
        if (t != Tab.TERMINAL && t != Tab.BROWSER) hideKeyboard()
        updateChrome()
    }

    /** Hide the bottom bar / action rows while the soft keyboard is up, and game controls unless enabled + portrait. */
    private fun updateChrome() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fun vis(v: View, show: Boolean) { val x = if (show) View.VISIBLE else View.GONE; if (v.visibility != x) v.visibility = x }
        vis(navBar, !keyboardShown)
        vis(actionRow, !keyboardShown && !gameMode)
        vis(gameControls, gameMode && !keyboardShown && !land)
        vis(subtitle, !keyboardShown && !land)
    }

    // ------------------------------------------------------------------ Terminal tab
    private fun buildTerminalPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(12), px(12), px(8)) }
        term = TerminalView(this, null).apply { isFocusable = true; isFocusableInTouchMode = true; setPadding(px(8), px(8), px(8), px(8)) }
        term.setTerminalViewClient(this); term.setTextSize(spToPx(fontSp)); term.setBackgroundColor(Ui.TERM_BG)
        val termFrame = FrameLayout(this)
        termFrame.addView(card(term, Ui.TERM_BG), FrameLayout.LayoutParams(MATCH, MATCH))
        errorPanel = buildErrorPanel().apply { visibility = View.GONE }
        termFrame.addView(errorPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(termFrame, lp(MATCH, 0, 1f))
        keysBar = buildKeysBar()
        page.addView(keysBar, lp(MATCH, px(42)).apply { topMargin = px(10) })
        gameControls = buildGameControls()
        page.addView(gameControls, lp(MATCH, WRAP).apply { topMargin = px(6) })
        val actions = LinearLayout(this)
        powerBtn = action(IconDrawable.Kind.POWER, "Power Off", Ui.DANGER, Color.WHITE) { togglePower() }
        actions.addView(powerBtn, lp(0, px(52), 1f).apply { marginEnd = px(8) })
        actions.addView(action(IconDrawable.Kind.RESTART, "Restart", Ui.SURFACE2, Ui.GREEN) { start() }, lp(0, px(52), 1f).apply { marginEnd = px(8) })
        actions.addView(action(IconDrawable.Kind.LESSONS, "Lessons", Ui.SURFACE2, Ui.GREEN) { selectTab(Tab.LESSONS) }, lp(0, px(52), 1f))
        actionRow = actions
        page.addView(actions, lp(MATCH, WRAP).apply { topMargin = px(10) })
        return page
    }

    private fun spToPx(sp: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics).toInt()

    /** One row: ^C ESC TAB CTRL ALT ← ↑ ↓ → / - |. Fits 360dp+ screens; scrolls sideways if narrower. Labels never wrap. */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildKeysBar(): View {
        val scroll = HorizontalScrollView(this).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val row = LinearLayout(this)
        val keys = listOf("^C", "ESC", "TAB", "CTRL", "ALT", "←", "↑", "↓", "→", "/", "-", "|")
        for (k in keys) {
            val v = TextView(this).apply {
                text = k; gravity = Gravity.CENTER; maxLines = 1; isSingleLine = true; includeFontPadding = false
                setTextColor(Ui.TEXT); textSize = if (k.length == 1) 15f else 11.5f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setAutoSizeTextTypeUniformWithConfiguration(8, if (k.length == 1) 15 else 12, 1, TypedValue.COMPLEX_UNIT_SP)
                setPadding(px(1), 0, px(1), 0); isFocusable = false; contentDescription = keyName(k)
                background = keyBg(false)
            }
            val arrow = "←↑↓→".indexOf(k)
            if (arrow >= 0) {
                val dir = "DACB"[arrow]
                v.setOnTouchListener(repeater { sendArrow(dir) })
            } else v.tap {
                when (k) {
                    "^C" -> session?.write(Keys.CTRL_C)
                    "ESC" -> session?.write(Keys.ESC)
                    "TAB" -> session?.write("\t")
                    "CTRL" -> { ctrlLatched = !ctrlLatched; refreshModifierKeys() }
                    "ALT" -> { altLatched = !altLatched; refreshModifierKeys() }
                    else -> sendText(k)
                }
            }
            if (k == "CTRL") ctrlKey = v
            if (k == "ALT") altKey = v
            // word keys get more room than one-character keys so CTRL/ESC never squeeze; single row stays >= 22dp per key
            row.addView(v, lp(px(if (k.length > 1) 32 else 24), MATCH).apply { marginEnd = if (k == keys.last()) 0 else px(3) })
        }
        scroll.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        // Share the available width: word keys (ESC, CTRL...) get 1.35 parts, one-character keys 0.85 parts. If that would
        // make a key narrower than 20dp (very small phones) keep fixed widths and let the row scroll sideways instead.
        scroll.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l == or - ol && row.tag == r - l) return@addOnLayoutChangeListener
            row.tag = r - l
            val avail = (r - l) - v.paddingLeft - v.paddingRight - px(3) * (keys.size - 1)
            val weights = keys.map { if (it.length > 1) 1.35f else 0.85f }
            val unit = avail / weights.sum()
            val fits = unit * 0.85f >= px(20)
            var used = 0
            for (i in keys.indices) {
                val w = when { !fits -> px(if (keys[i].length > 1) 32 else 24); i == keys.lastIndex -> avail - used; else -> (unit * weights[i]).toInt() }
                used += w
                row.getChildAt(i).layoutParams = (row.getChildAt(i).layoutParams as LinearLayout.LayoutParams).apply { width = w }
            }
            row.post { row.requestLayout() }
        }
        return scroll
    }

    private fun keyName(k: String) = when (k) { "^C" -> "Control C (stop program)"; "←" -> "Left"; "↑" -> "Up"; "↓" -> "Down"; "→" -> "Right"; else -> k }
    private fun keyBg(armed: Boolean) = Ui.rounded(if (armed) Ui.ACCENT else Ui.SURFACE2, px(9).toFloat(), if (armed) Ui.ACCENT else Ui.OUTLINE, px(1))

    /** CTRL / ALT are one-shot toggles: highlighted while armed, released after the next key. */
    private fun refreshModifierKeys() {
        ctrlKey.background = keyBg(ctrlLatched); ctrlKey.setTextColor(if (ctrlLatched) Color.WHITE else Ui.TEXT)
        altKey.background = keyBg(altLatched); altKey.setTextColor(if (altLatched) Color.WHITE else Ui.TEXT)
    }

    private fun sendText(s: String) {
        val sess = session ?: return
        if (ctrlLatched || altLatched) {
            for (cp in s.codePoints()) sess.write(Keys.modified(cp, ctrlLatched, altLatched))
            ctrlLatched = false; altLatched = false; refreshModifierKeys()
        } else sess.write(s)
    }

    private fun repeater(action: () -> Unit) = object : View.OnTouchListener {
        val r = object : Runnable { override fun run() { action(); ui.postDelayed(this, 70) } }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; sounds.click(); action(); v.performHapticFeedback(1); ui.postDelayed(r, 380) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; ui.removeCallbacks(r) }
            }
            return true
        }
    }

    fun sendArrow(dir: Char) {
        val s = session ?: return
        s.write(Keys.arrow(dir, s.emulator?.isCursorKeysApplicationMode == true))
    }

    /** Optional handheld controls ("Game mode"): D-pad = arrows, A = Enter, B = Ctrl-C (long-press = Esc), START = GPIO, SELECT = keyboard. */
    private fun buildGameControls(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), px(4), px(4), px(2)) }
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = px(38)
        fun arrow(label: String, dir: Char) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; setTextColor(Ui.TEXT2); textSize = 13f
            background = Ui.rounded(Ui.SURFACE2, px(6).toFloat()); setOnTouchListener(repeater { sendArrow(dir) })
        }
        fun gridRow(vararg v: View?) = LinearLayout(this).apply { for (x in v) addView(x ?: View(this@MainActivity), lp(s, s)) }
        pad.addView(gridRow(null, arrow("▲", 'A'), null))
        pad.addView(gridRow(arrow("◀", 'D'), View(this).apply { setBackgroundColor(Ui.SURFACE2) }, arrow("▶", 'C')))
        pad.addView(gridRow(null, arrow("▼", 'B'), null))
        row.addView(pad, lp(WRAP, WRAP))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        fun pill(label: String, onClick: () -> Unit) = text(label, 11f, Ui.TEXT2, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; background = Ui.rounded(Ui.SURFACE2, px(14).toFloat(), Ui.OUTLINE, px(1)); tap { onClick() } }
        mid.addView(pill("START") { selectTab(Tab.GPIO) }, lp(px(76), px(28)).apply { bottomMargin = px(8) })
        mid.addView(pill("SELECT") { toggleKeyboard() }, lp(px(76), px(28)))
        row.addView(mid, lp(0, WRAP, 1f))
        fun round(label: String, caption: String, color: Int, topPad: Int, click: () -> Unit, long: (() -> Unit)? = null) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, px(topPad), 0, 0)
            addView(text(label, 18f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                tap { click(); it.performHapticFeedback(1) }
                if (long != null) setOnLongClickListener { long(); it.performHapticFeedback(0); true }
            }, lp(px(52), px(52)))
            addView(text(caption, 10f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = px(3) })
        }
        row.addView(round("B", "STOP", Ui.ACCENT, 30, { session?.write(Keys.CTRL_C) }, { session?.write(Keys.ESC); toast("Esc") }), lp(WRAP, WRAP).apply { marginEnd = px(10) })
        row.addView(round("A", "ENTER", Color.rgb(0x2E, 0x9E, 0x4F), 0, { session?.write("\r") }), lp(WRAP, WRAP))
        return row
    }

    @VisibleForTesting fun togglePower() {
        if (session?.isRunning == true) { Linux.powerOff(this); setStatus(Status.STOPPED) } else start()
    }

    // ------------------------------------------------------------------ startup error panel
    private fun buildErrorPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(20), px(16), px(20), px(16))
            background = Ui.rounded(Ui.SURFACE, px(16).toFloat(), Ui.OUTLINE, px(1)); isClickable = true
        }
        box.addView(icon(IconDrawable.Kind.POWER, Color.rgb(0xFF, 0x6B, 0x6B), 32), lp(px(32), px(32)))
        errorTitle = text("", 19f, Ui.TEXT, true).apply { setLineSpacing(0f, 1.1f) }
        box.addView(errorTitle, lp(MATCH, WRAP).apply { topMargin = px(12) })
        errorMsg = text("", 14f, Ui.TEXT2).apply { setLineSpacing(0f, 1.25f) }
        box.addView(errorMsg, lp(MATCH, WRAP).apply { topMargin = px(8) })
        val row = LinearLayout(this)
        row.addView(action(IconDrawable.Kind.RESTART, "Try again", Color.rgb(0x2E, 0x9E, 0x4F), Color.WHITE) { start() }
            .apply { tag = "retry" }, lp(0, px(48), 1f).apply { marginEnd = px(8) })
        row.addView(action(IconDrawable.Kind.FILE, "Copy details", Ui.SURFACE2, Ui.TEXT) { CrashActivity.copy(this, errorDetails) }
            .apply { tag = "copy" }, lp(0, px(48), 1f))
        box.addView(row, lp(MATCH, WRAP).apply { topMargin = px(18) })
        return ScrollView(this).apply { isFillViewport = true; addView(box) }
    }

    private fun showError(f: StartupErrors.Friendly, details: String) {
        errorTitle.text = f.title; errorMsg.text = f.message; errorDetails = details
        errorPanel.visibility = View.VISIBLE; setStatus(Status.ERROR)
        if (currentTab != Tab.TERMINAL) selectTab(Tab.TERMINAL)
    }

    private fun hideError() { errorPanel.visibility = View.GONE }

    @VisibleForTesting fun errorShown(): String? = if (errorPanel.visibility == View.VISIBLE) errorTitle.text.toString() else null

    enum class Status { STARTING, RUNNING, STOPPED, ERROR }
    @VisibleForTesting var status = Status.STARTING; private set
    private fun setStatus(s: Status) {
        status = s
        val (label, color, bg) = when (s) {
            Status.STARTING -> Triple("Starting", Ui.AMBER, Color.rgb(0x3A, 0x2E, 0x12))
            Status.RUNNING -> Triple("Running", Ui.GREEN, Ui.GREEN_BG)
            Status.STOPPED -> Triple("Stopped", Ui.TEXT2, Ui.SURFACE2)
            Status.ERROR -> Triple("Error", Color.rgb(0xFF, 0x6B, 0x6B), Color.rgb(0x3A, 0x16, 0x1C))
        }
        statusPill.text = label; statusPill.setTextColor(color); statusDot.setColor(color)
        statusPill.background = Ui.rounded(bg, px(20).toFloat())
        val on = s == Status.RUNNING || s == Status.STARTING
        (powerBtn.findViewWithTag<TextView>("label")).text = if (on) "Power Off" else "Power On"
        powerBtn.background = Ui.rounded(if (on) Ui.DANGER else Color.rgb(0x2E, 0x9E, 0x4F), px(14).toFloat())
    }

    // ------------------------------------------------------------------ Files tab
    private fun homeDir() = File(filesDir, "rootfs/home/pi")

    private fun buildFilesPage(): View {
        val frame = FrameLayout(this)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(10), px(12), px(8)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filesUp = iconButton(IconDrawable.Kind.UP) { filesDirCur?.parentFile?.let { if (it.path.startsWith(homeDir().path)) showDir(it) } }.apply { contentDescription = "Up" }
        bar.addView(filesUp, lp(px(42), px(42)))
        filesPath = text("~", 15f, Ui.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START; typeface = Typeface.MONOSPACE }
        bar.addView(filesPath, lp(0, WRAP, 1f).apply { marginStart = px(4) })
        bar.addView(iconButton(IconDrawable.Kind.RELOAD) { showDir(filesDirCur ?: homeDir()) }.apply { contentDescription = "Refresh" }, lp(px(42), px(42)))
        page.addView(bar, lp(MATCH, WRAP))
        filesList = ListView(this).apply { divider = null; dividerHeight = px(4); selector = GradientDrawable(); isSoundEffectsEnabled = false }
        page.addView(card(filesList), lp(MATCH, 0, 1f).apply { topMargin = px(8) })
        frame.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        viewer = buildViewer(); viewer.visibility = View.GONE
        frame.addView(viewer, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    private fun buildViewer(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(px(12), px(10), px(12), px(8)); isClickable = true }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(iconButton(IconDrawable.Kind.BACK) { viewer.visibility = View.GONE }.apply { contentDescription = "Back" }, lp(px(42), px(42)))
        viewerTitle = text("", 15f, Ui.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        bar.addView(viewerTitle, lp(0, WRAP, 1f).apply { marginStart = px(4); marginEnd = px(8) })
        viewerRun = action(IconDrawable.Kind.PLAY, "Run", Color.rgb(0x2E, 0x9E, 0x4F), Color.WHITE) { viewerFile?.let { runPy(it) } }
        bar.addView(viewerRun, lp(px(96), px(40)))
        page.addView(bar, lp(MATCH, WRAP))
        viewerText = text("", 12.5f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(px(12), px(12), px(12), px(12)); setLineSpacing(0f, 1.15f) }
        val sv = ScrollView(this).apply { addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(viewerText) }) }
        page.addView(card(sv, Ui.TERM_BG), lp(MATCH, 0, 1f).apply { topMargin = px(8) })
        return page
    }

    private fun guestPath(f: File) = "/home/pi" + f.path.removePrefix(homeDir().path)

    fun showDir(dir: File) {
        filesDirCur = dir
        val rel = guestPath(dir).replaceFirst("/home/pi", "~")
        filesPath.text = rel
        filesUp.alpha = if (dir.path == homeDir().path) 0.3f else 1f
        val entries = (dir.listFiles()?.toList() ?: emptyList()).filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !(it.isDirectory && !Files.isSymbolicLink(it.toPath())) }, { it.name.lowercase() }))
        filesList.adapter = FileAdapter(entries, if (dir.exists()) "Nothing here yet" else "Linux isn't installed yet. Open the Terminal tab first.")
        filesList.setOnItemClickListener { _, _, pos, _ ->
            val f = entries.getOrNull(pos) ?: return@setOnItemClickListener
            sounds.click()
            when {
                Files.isSymbolicLink(f.toPath()) -> toast("Symlink: open it from the terminal")
                f.isDirectory -> showDir(f)
                else -> openFile(f)
            }
        }
    }

    private inner class FileAdapter(val items: List<File>, val empty: String) : BaseAdapter() {
        override fun getCount() = maxOf(1, items.size)
        override fun getItem(p: Int) = items.getOrNull(p)
        override fun getItemId(p: Int) = p.toLong()
        override fun isEnabled(p: Int) = items.isNotEmpty()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val f = items.getOrNull(p) ?: return text(empty, 14f, Ui.TEXT2).apply { setPadding(px(16), px(20), px(16), px(20)) }
            val link = Files.isSymbolicLink(f.toPath())
            val dir = f.isDirectory && !link
            return LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(14), px(12), px(14), px(12))
                addView(icon(if (dir) IconDrawable.Kind.FOLDER else IconDrawable.Kind.FILE, if (dir) Ui.GREEN else if (f.name.endsWith(".py")) Ui.ACCENT else Ui.TEXT2, 22))
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text(f.name + if (link) "  →" else "", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                val meta = (if (dir) "${f.list()?.size ?: 0} items" else humanSize(f.length())) + " · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.lastModified()))
                col.addView(text(meta, 12f, Ui.TEXT2), lp(WRAP, WRAP).apply { topMargin = px(4) })
                addView(col, lp(0, WRAP, 1f).apply { marginStart = px(14) })
                if (f.name.endsWith(".py")) addView(iconButton(IconDrawable.Kind.PLAY, Ui.GREEN) { runPy(f) }.apply { contentDescription = "Run ${f.name}" }, lp(px(40), px(40)))
            }
        }
    }

    private fun humanSize(n: Long) = when { n < 1024 -> "$n B"; n < 1 shl 20 -> "${n / 1024} KB"; else -> String.format(Locale.US, "%.1f MB", n / 1048576.0) }

    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024)); val buf = ByteArray(16 * 1024)
        while (out.size() < max) { val n = input.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    @VisibleForTesting internal fun openFile(f: File) {
        // (not InputStream.readNBytes: that is API 33 and would crash the viewer on Android 8-12)
        val bytes = try { f.inputStream().use { readUpTo(it, 512 * 1024) } } catch (e: Exception) { toast("Can't open: ${e.message}"); return }
        if (bytes.take(4096).any { it == 0.toByte() }) { toast("Binary file - not shown"); return }
        viewerFile = f; viewerTitle.text = f.name
        viewerText.text = String(bytes) + if (f.length() > bytes.size) "\n… (first 512 KB shown)" else ""
        viewerRun.visibility = if (f.name.endsWith(".py")) View.VISIBLE else View.GONE
        viewer.visibility = View.VISIBLE
    }

    private fun runPy(f: File) {
        val p = guestPath(f)
        val d = p.substringBeforeLast('/')
        runInTerminal("cd ${Keys.shQuote(d)} && python3 ${Keys.shQuote(p.substringAfterLast('/'))}", Tab.TERMINAL)
    }

    /** Types a command into the shell: Ctrl-C stops whatever example is still running, Ctrl-U clears a half-typed line. */
    fun runInTerminal(cmd: String, then: Tab) {
        val s = session
        if (s == null || !s.isRunning) { toast("Linux isn't running - tap Power On"); selectTab(Tab.TERMINAL); return }
        s.write(Keys.CTRL_C)
        ui.postDelayed({ if (s.isRunning) s.write("\u0015$cmd\r") }, 300)
        gpioRunning.text = "Running: $cmd"
        selectTab(then)
    }

    // ------------------------------------------------------------------ GPIO tab
    private fun buildGpioPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(10), px(12), px(8)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("40-pin header", 17f, Ui.TEXT, true))
        gpioRunning = text("LEDs show outputs · hold a square button to press an input", 12f, Ui.TEXT2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        col.addView(gpioRunning, lp(WRAP, WRAP).apply { topMargin = px(4) })
        bar.addView(col, lp(0, WRAP, 1f))
        bar.addView(action(IconDrawable.Kind.STOP, "Stop", Ui.DANGER, Color.WHITE) {
            session?.write(Keys.CTRL_C); gpioRunning.text = "Sent Ctrl-C (stop)"
        }.apply { contentDescription = "Stop program (Ctrl-C)" }, lp(px(92), px(42)).apply { marginStart = px(8) })
        page.addView(bar, lp(MATCH, WRAP))
        val sv = ScrollView(this).apply { addView(HeaderView(this@MainActivity, Gpio.core).apply { onPress = { sounds.click() } }); isFillViewport = true }
        page.addView(card(sv), lp(MATCH, 0, 1f).apply { topMargin = px(10) })
        return page
    }

    // ------------------------------------------------------------------ Browser tab
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildBrowserPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8), px(8), px(12), px(8)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        web = WebView(this)
        bar.addView(iconButton(IconDrawable.Kind.BACK) { if (web.canGoBack()) web.goBack() }.apply { contentDescription = "Back" }, lp(px(40), px(40)))
        bar.addView(iconButton(IconDrawable.Kind.FORWARD) { if (web.canGoForward()) web.goForward() }.apply { contentDescription = "Forward" }, lp(px(40), px(40)))
        bar.addView(iconButton(IconDrawable.Kind.RELOAD) { web.reload() }.apply { contentDescription = "Reload" }, lp(px(40), px(40)))
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.rounded(Ui.SURFACE2, px(22).toFloat()); setPadding(px(12), 0, px(10), 0)
        }
        field.addView(icon(IconDrawable.Kind.LOCK, Ui.TEXT2, 16), lp(px(16), px(16)).apply { marginEnd = px(8) })
        urlBar = EditText(this).apply {
            setSingleLine(); textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.TEXT2); hint = "Search or type URL / port"
            background = null; setPadding(0, 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        field.addView(urlBar, lp(0, WRAP, 1f))
        bar.addView(field, lp(0, px(44), 1f).apply { marginStart = px(4) })
        page.addView(bar, lp(MATCH, WRAP))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false      // web pages can't read app files
        web.settings.allowContentAccess = false
        web.setBackgroundColor(Ui.SURFACE)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = req.url.scheme !in setOf("http", "https")
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { if (url != null && url.startsWith("http")) urlBar.setText(url) }
        }
        page.addView(card(web), lp(MATCH, 0, 1f).apply { topMargin = px(8); marginStart = px(4) })
        val actions = LinearLayout(this)
        actions.addView(action(IconDrawable.Kind.BOOKMARK, "Bookmarks", Ui.ACCENT, Color.WHITE) { showBookmarks() }, lp(0, px(52), 1.2f).apply { marginEnd = px(8) })
        actions.addView(action(IconDrawable.Kind.STAR, "Add", Ui.SURFACE2, Ui.TEXT) { addBookmark() }, lp(0, px(52), 0.8f).apply { marginEnd = px(8) })
        actions.addView(action(IconDrawable.Kind.PLUS, "New Tab", Ui.SURFACE2, Ui.TEXT) { newTab() }, lp(0, px(52), 1.1f))
        page.addView(actions, lp(MATCH, WRAP).apply { topMargin = px(10); marginStart = px(4) })
        return page
    }

    private fun bookmarks(): MutableList<Pair<String, String>> =
        (prefs.getString("bookmarks", null) ?: DEFAULT_BOOKMARKS).lines().filter { '|' in it }
            .map { it.substringBefore('|') to it.substringAfter('|') }.toMutableList()

    private fun showBookmarks() {
        val bm = bookmarks()
        val cur = web.url?.takeIf { it.startsWith("http") }
        val labels = bm.map { "${it.first}\n${it.second}" }.toMutableList()
        if (cur != null && bm.none { it.second == cur }) labels += "★ Add this page"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Bookmarks")
            .setItems(labels.toTypedArray()) { _, i -> if (i < bm.size) openBrowser(bm[i].second) else addBookmark() }
            .setNegativeButton("Close", null).show()
    }

    private fun addBookmark() {
        val cur = web.url?.takeIf { it.startsWith("http") } ?: run { toast("Open a web page first"); return }
        val bm = bookmarks()
        if (bm.any { it.second == cur }) { toast("Already bookmarked"); return }
        bm += (web.title?.takeIf { it.isNotBlank() } ?: cur) to cur
        prefs.edit().putString("bookmarks", bm.joinToString("\n") { "${it.first.replace('|', ' ').replace('\n', ' ')}|${it.second}" }).apply()
        toast("Bookmarked")
    }

    /** Single-tab browser: "New Tab" clears to the start page and puts the cursor in the address bar. */
    private fun newTab() {
        web.loadDataWithBaseURL(null, START_PAGE, "text/html", "utf-8", null); web.clearHistory()
        urlBar.setText(""); urlBar.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(urlBar, 0)
    }

    @VisibleForTesting fun go() { val t = urlBar.text.toString(); if (t.isBlank()) return; web.loadUrl(normalise(t)); hideKeyboard(urlBar); web.requestFocus() }

    private fun openBrowser(url: String?) {
        selectTab(Tab.BROWSER)
        if (url != null) { urlBar.setText(url); web.loadUrl(url) }
    }

    // ------------------------------------------------------------------ Lessons tab
    private fun buildLessonsPage(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(12), px(12), px(12)) }
        list.addView(text("Missions", 20f, Ui.TEXT, true))
        list.addView(text("Each one runs a program from ~/examples and opens the GPIO tab so you can watch the pins. Tap Stop (or ^C) to end it.", 13f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) },
            lp(MATCH, WRAP).apply { topMargin = px(6); bottomMargin = px(6) })
        for ((i, l) in Lessons.all.withIndex()) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = Ui.rounded(Ui.SURFACE, px(16).toFloat(), Ui.OUTLINE, px(1)); setPadding(px(16), px(14), px(14), px(14))
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text("${i + 1}", 14f, Color.WHITE, true).apply { gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i < 4) Ui.ACCENT else Color.rgb(0x2E, 0x9E, 0x4F)) } }, lp(px(28), px(28)))
            val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tcol.addView(text(l.level.uppercase(), 10.5f, Ui.GREEN, true).apply { letterSpacing = 0.08f })
            tcol.addView(text(l.title, 16f, Ui.TEXT, true), lp(WRAP, WRAP).apply { topMargin = px(3) })
            top.addView(tcol, lp(0, WRAP, 1f).apply { marginStart = px(12) })
            top.addView(action(IconDrawable.Kind.PLAY, "Run", Color.rgb(0x2E, 0x9E, 0x4F), Color.WHITE) { runInTerminal(Lessons.command(l), Tab.GPIO) }
                .apply { contentDescription = "Run ${l.title}" }, lp(px(84), px(40)))
            c.addView(top)
            c.addView(text(l.text, 13.5f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP).apply { topMargin = px(10) })
            c.addView(text("$ ${Lessons.command(l)}", 12f, Ui.GREEN).apply { typeface = Typeface.MONOSPACE; maxLines = 1 }, lp(MATCH, WRAP).apply { topMargin = px(8) })
            list.addView(c, lp(MATCH, WRAP).apply { topMargin = px(10) })
        }
        return ScrollView(this).apply { addView(list) }
    }

    // ------------------------------------------------------------------ Settings tab
    private fun buildSettingsPage(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(12), px(12), px(12)) }
        list.addView(text("Settings", 20f, Ui.TEXT, true), lp(WRAP, WRAP).apply { bottomMargin = px(8) })
        fun row(kind: IconDrawable.Kind, title: String, sub: String, end: View): View = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.rounded(Ui.SURFACE, px(16).toFloat(), Ui.OUTLINE, px(1)); setPadding(px(16), px(14), px(12), px(14))
            addView(icon(kind, Ui.GREEN, 22))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(title, 15.5f, Ui.TEXT, true))
            col.addView(text(sub, 12.5f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, lp(WRAP, WRAP).apply { topMargin = px(4) })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = px(14); marginEnd = px(8) })
            addView(end)
        }
        fun sw(on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
            isChecked = on; setOnCheckedChangeListener { _, c -> f(c) }; isSoundEffectsEnabled = false; setOnClickListener { sounds.click() } }
        lcdSwitch = sw(lcd) { setLcd(it) }.apply { contentDescription = "LCD green tint" }
        gameSwitch = sw(gameMode) { setGameMode(it) }.apply { contentDescription = "Game mode" }
        list.addView(row(IconDrawable.Kind.TERMINAL, "LCD green tint", "Retro green-screen colours for the terminal", lcdSwitch), lp(MATCH, WRAP).apply { topMargin = px(8) })
        list.addView(row(IconDrawable.Kind.GAMEPAD, "Game mode", "Handheld controls on the Terminal tab: D-pad = arrows, A = Enter, B = Ctrl-C (hold: Esc), START = GPIO, SELECT = keyboard", gameSwitch), lp(MATCH, WRAP).apply { topMargin = px(8) })
        list.addView(row(IconDrawable.Kind.SPEAKER, "Button click sounds", "A soft click when you press a button",
            sw(sounds.clicksOn) { sounds.setClicks(it) }.apply { contentDescription = "Button click sounds" }), lp(MATCH, WRAP).apply { topMargin = px(8) })
        list.addView(row(IconDrawable.Kind.KEYBOARD, "Typing sounds", "Quiet keyboard ticks while the terminal prints. Long bursts go quiet",
            sw(sounds.typingOn) { sounds.setTyping(it) }.apply { contentDescription = "Typing sounds" }), lp(MATCH, WRAP).apply { topMargin = px(8) })
        val vol = SeekBar(this).apply {
            max = 100; progress = sounds.volume; contentDescription = "Sound volume"; isSoundEffectsEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) sounds.setVolume(v) }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) { sounds.preview() }
            })
        }
        list.addView(row(IconDrawable.Kind.SPEAKER, "Sound volume", "Silent when the phone is on silent or vibrate",
            vol.apply { layoutParams = lp(px(132), WRAP) }), lp(MATCH, WRAP).apply { topMargin = px(8) })
        val font = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun step(label: String, d: Int) = text(label, 18f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER; background = Ui.rounded(Ui.SURFACE2, px(10).toFloat(), Ui.OUTLINE, px(1)); tap { setFont(fontSp + d) } }
        font.addView(step("−", -1).apply { contentDescription = "Smaller terminal text" }, lp(px(38), px(38)))
        fontLabel = text("$fontSp", 15f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        font.addView(fontLabel, lp(px(34), WRAP))
        font.addView(step("+", 1).apply { contentDescription = "Larger terminal text" }, lp(px(38), px(38)))
        list.addView(row(IconDrawable.Kind.KEYBOARD, "Font size", "Terminal text size (sp)", font), lp(MATCH, WRAP).apply { topMargin = px(8) })
        list.addView(row(IconDrawable.Kind.RESTART, "Restart Linux", "Starts a fresh shell. Your files are kept.",
            action(IconDrawable.Kind.RESTART, "Restart", Ui.SURFACE2, Ui.GREEN) { selectTab(Tab.TERMINAL); start() }.apply { layoutParams = lp(px(104), px(40)) }), lp(MATCH, WRAP).apply { topMargin = px(8) })
        list.addView(row(IconDrawable.Kind.LESSONS, "About & licenses", "Version, open-source licenses, source code offer, privacy",
            action(IconDrawable.Kind.FORWARD, "Open", Ui.SURFACE2, Ui.GREEN) { showAbout() }.apply { layoutParams = lp(px(92), px(40)); contentDescription = "Open About and licenses" }),
            lp(MATCH, WRAP).apply { topMargin = px(8) })
        val about = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), px(18), px(4), px(4)) }
        about.addView(appIcon(36), lp(px(36), px(36)).apply { marginEnd = px(12) })
        about.addView(text("${About.header()} Collects no data.\n\n${About.TRADEMARK}",
            12f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, lp(0, WRAP, 1f))
        list.addView(about, lp(MATCH, WRAP))
        return ScrollView(this).apply { addView(list) }
    }

    // ------------------------------------------------------------------ About & licenses
    private fun buildAboutOverlay(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); fitsSystemWindows = true; isClickable = true; visibility = View.GONE
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(6), px(6), px(12), px(6)) }
        bar.addView(iconButton(IconDrawable.Kind.BACK) { aboutBack() }.apply { contentDescription = "Back"; tag = "aboutBack" }, lp(px(44), px(44)))
        bar.addView(text("About & licenses", 18f, Ui.TEXT, true), lp(0, WRAP, 1f).apply { marginStart = px(6) })
        outer.addView(bar, lp(MATCH, WRAP))
        outer.addView(View(this).apply { setBackgroundColor(Ui.OUTLINE) }, lp(MATCH, px(1)))
        val body = FrameLayout(this)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(24)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(appIcon(44), lp(px(44), px(44)).apply { marginEnd = px(12) })
        top.addView(text(About.header(), 14f, Ui.TEXT).apply { setLineSpacing(0f, 1.2f); tag = "aboutVersion" }, lp(0, WRAP, 1f))
        list.addView(top, lp(MATCH, WRAP))
        fun section(title: String, body: String) {
            list.addView(text(title, 15f, Ui.GREEN, true), lp(MATCH, WRAP).apply { topMargin = px(18) })
            list.addView(text(body, 13f, Ui.TEXT2).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(MATCH, WRAP).apply { topMargin = px(6) })
        }
        section("Privacy", About.PRIVACY)
        section("Source code", About.sourceOffer())
        section("Trademarks", About.TRADEMARK)
        list.addView(text("Open-source licenses", 15f, Ui.GREEN, true), lp(MATCH, WRAP).apply { topMargin = px(18) })
        fun entry(title: String, sub: String, asset: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(10), px(14), px(10)); isClickable = true
            background = Ui.rounded(Ui.SURFACE, px(12).toFloat(), Ui.OUTLINE, px(1))
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, Ui.rounded(Color.WHITE, px(12).toFloat()))
            addView(text(title, 14f, Ui.TEXT, true), lp(MATCH, WRAP))
            addView(text(sub, 12f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, lp(MATCH, WRAP).apply { topMargin = px(3) })
            contentDescription = "$title, $sub. Show license"
            tag = asset
            tap { showLegal(title, asset) }
        }
        list.addView(entry("All third-party notices", "Every component, copyright and source location", About.NOTICES), lp(MATCH, WRAP).apply { topMargin = px(8) })
        for (c in About.COMPONENTS) list.addView(entry(c.name, "${c.license} · ${c.what}", c.file), lp(MATCH, WRAP).apply { topMargin = px(8) })
        aboutList = ScrollView(this).apply { addView(list) }
        body.addView(aboutList, FrameLayout.LayoutParams(MATCH, MATCH))

        aboutText = text("", 12f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(px(14), px(12), px(14), px(24)); includeFontPadding = true }
        aboutTextScroll = ScrollView(this).apply { addView(aboutText); visibility = View.GONE }
        body.addView(aboutTextScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        outer.addView(body, lp(MATCH, 0, 1f))
        return outer
    }

    @VisibleForTesting fun showAbout() {
        aboutTextScroll.visibility = View.GONE; aboutList.visibility = View.VISIBLE
        aboutPanel.visibility = View.VISIBLE
    }

    @VisibleForTesting fun showLegal(title: String, asset: String) {
        aboutText.text = "$title\n\n${About.read(this, asset)}"
        aboutTextScroll.scrollTo(0, 0); aboutList.visibility = View.GONE; aboutTextScroll.visibility = View.VISIBLE
    }

    /** Back inside About: licence text -> list -> close. Returns false when About wasn't open. */
    @VisibleForTesting fun aboutBack(): Boolean {
        if (aboutPanel.visibility != View.VISIBLE) return false
        if (aboutTextScroll.visibility == View.VISIBLE) { aboutTextScroll.visibility = View.GONE; aboutList.visibility = View.VISIBLE }
        else aboutPanel.visibility = View.GONE
        return true
    }

    @VisibleForTesting fun aboutState(): String = when {
        aboutPanel.visibility != View.VISIBLE -> "closed"
        aboutTextScroll.visibility == View.VISIBLE -> "text:" + aboutText.text.toString().take(200)
        else -> "list"
    }

    @VisibleForTesting fun setFont(sp: Int) {
        fontSp = sp.coerceIn(7, 24); prefs.edit().putInt("fontSp", fontSp).apply()
        fontLabel.text = "$fontSp"; term.setTextSize(spToPx(fontSp))
    }

    // ------------------------------------------------------------------ LCD palette
    fun setLcd(on: Boolean) { lcd = on; prefs.edit().putBoolean("lcd", on).apply(); if (lcdSwitch.isChecked != on) lcdSwitch.isChecked = on; applyPalette() }
    fun setGameMode(on: Boolean) { gameMode = on; prefs.edit().putBoolean("game", on).apply(); if (gameSwitch.isChecked != on) gameSwitch.isChecked = on; updateChrome() }

    private fun applyPalette() {
        val bg = if (lcd) LCD[3] else Ui.TERM_BG
        term.setBackgroundColor(bg); (term.parent as? View)?.background = Ui.rounded(bg, px(16).toFloat(), Ui.OUTLINE, px(1))
        val emu = session?.emulator ?: return
        emu.mColors.reset()
        val c = emu.mColors.mCurrentColors
        if (lcd) {
            for (i in 0 until 256) {
                val v = c[i]; val l = (0.299 * Color.red(v) + 0.587 * Color.green(v) + 0.114 * Color.blue(v)) / 255
                c[i] = when { i == 0 -> LCD[3]; l > 0.6 -> LCD[0]; l > 0.35 -> LCD[1]; else -> LCD[2] }
            }
            c[256] = LCD[0]; c[257] = LCD[3]; c[258] = LCD[1]
        } else { c[257] = Ui.TERM_BG; c[258] = Ui.GREEN }
        term.invalidate()
    }

    // ------------------------------------------------------------------ splash (sand animation)
    private fun buildSplash() = SandView(this).apply {
        isClickable = true; contentDescription = "PiSandbox is starting. Tap to skip the animation."
        setOnClickListener { hideSplash() }
        onIntroDone = { introDone = true; maybeHideSplash() }
    }

    private fun showSplash(firstRun: Boolean) {
        splash.reduceMotion = !ValueAnimator.areAnimatorsEnabled()  // "Remove animations" / animator scale 0
        if (firstRun) { splash.setSetupProgress(0f); splash.caption = "Setting up Linux…" }
        splash.visibility = View.VISIBLE; splash.alpha = 1f; splash.startIntro()
    }

    private fun noSplash() { splash.stop(); splash.visibility = View.GONE; introDone = true }

    private fun maybeHideSplash() { if (introDone && ready) hideSplash() }

    private fun hideSplash() {
        if (splashHiding || splash.visibility != View.VISIBLE) return
        splashHiding = true
        splash.animate().alpha(0f).setDuration(if (splash.reduceMotion) 0 else 300).withEndAction {
            splash.visibility = View.GONE; splash.stop(); splashHiding = false
        }.start()
    }

    // ------------------------------------------------------------------ session
    private fun start() {
        if (Linux.preparing) return
        if (testMode) { testStarts++; hideError(); setStatus(Status.STARTING); return }   // unit tests: no real proot
        if (session?.isRunning == true || Linux.isRunning) { Linux.stopRequested = true; Linux.session?.finishIfRunning() }
        hideError(); setStatus(Status.STARTING); ready = false
        Linux.preparing = true
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "prepare") {
            val result = try { Result.success(Sandbox(app).prepare { msg -> main.post { Linux.host?.get()?.onProgress(msg) } }) }
                         catch (t: Throwable) { Log.e(TAG, "prepare failed", t); Result.failure(t) }
            main.post {
                Linux.preparing = false
                val a = Linux.host?.get()
                if (a != null && !a.isDestroyed) a.onPrepared(result) else Linux.pending = result
            }
        }
    }

    fun onProgress(msg: String) {
        val pct = PCT.find(msg)?.groupValues?.get(1)?.toIntOrNull()
        val friendly = when {
            pct != null -> { splash.setSetupProgress(pct / 100f); "Setting up Linux… $pct%" }
            msg.startsWith("Rootfs ready") -> { splash.finishSetup(); "Linux is ready" }
            msg.startsWith("First run") -> "Setting up Linux…"
            else -> msg
        }
        splash.caption = friendly; subtitle.text = friendly
    }

    @VisibleForTesting fun onPrepared(result: Result<LaunchSpec>) {
        subtitle.text = "Debian 12 • arm64"
        result.onSuccess { spec ->
            val s = TerminalSession(spec.exe, spec.cwd, spec.argv, spec.env, 2000, this)
            Linux.session = s; Linux.startedAt = SystemClock.uptimeMillis(); Linux.stopRequested = false
            session = s; term.attachSession(s)
            setStatus(Status.RUNNING); hideError()
            if (currentTab == Tab.TERMINAL) term.requestFocus()
            if (!testMode) LinuxService.start(this)
            ready = true; maybeHideSplash()
        }.onFailure { e ->
            ready = true; hideSplash()
            showError(StartupErrors.describe(e), CrashReport.describe(this, e, "Linux setup failed"))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun toggleKeyboard() = if (keyboardShown) hideKeyboard() else showKeyboard()
    private fun showKeyboard() {
        term.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(term, 0)
    }
    private fun hideKeyboard(v: View = term) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    @Deprecated("Activity back handling (targetSdk 28)")
    override fun onBackPressed() {
        when {
            aboutPanel.visibility == View.VISIBLE -> aboutBack()
            currentTab == Tab.BROWSER && web.canGoBack() -> web.goBack()
            currentTab == Tab.FILES && viewer.visibility == View.VISIBLE -> viewer.visibility = View.GONE
            currentTab == Tab.FILES && filesDirCur != null && filesDirCur!!.path != homeDir().path -> showDir(filesDirCur!!.parentFile!!)
            currentTab != Tab.TERMINAL -> selectTab(Tab.TERMINAL)
            else -> moveTaskToBack(true) // like Home: Linux keeps running (notification has Power off)
        }
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) { term.onScreenUpdated(); onTerminalOutput() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession !== session) return
        if (!testMode) LinuxService.stop(this)
        val ms = SystemClock.uptimeMillis() - Linux.startedAt
        val code = finishedSession.exitStatus
        if (!Linux.stopRequested && ms < 5000 && code != 0) {
            val screen = runCatching { finishedSession.emulator?.screen?.transcriptText.orEmpty().trim().lines().takeLast(40).joinToString("\n") }.getOrDefault("")
            showError(StartupErrors.earlyExit(code), CrashReport.describe(this, null, "Linux shell exited with code $code after $ms ms") + "\n--- terminal\n" + screen)
        } else if (!Linux.preparing) setStatus(Status.STOPPED)   // (a Restart is already preparing the next shell)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("term", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return
        clip.getItemAt(0)?.coerceToText(this)?.toString()?.let { this.session?.emulator?.paste(it) }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { applyPalette() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ---- TerminalViewClient ----
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean = ctrlLatched
    override fun readAltKey() = altLatched
    override fun readShiftKey() = false
    override fun readFnKey() = false
    /** Soft-keyboard text arrives here: with CTRL/ALT armed we translate it ourselves (CTRL then c -> 0x03) and release the latch. */
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (!ctrlLatched && !altLatched) return false
        session?.write(Keys.modified(codePoint, ctrlLatched || ctrlDown, altLatched))
        ctrlLatched = false; altLatched = false
        runOnUiThread { refreshModifierKeys() }
        return true
    }
    override fun onEmulatorSet() { applyPalette() }

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }

    @VisibleForTesting fun attachTestSession(s: TerminalSession) { session = s; Linux.session = s; Linux.startedAt = SystemClock.uptimeMillis() }

    companion object {
        private const val TAG = "PiSandbox"
        private val PCT = Regex("(\\d{1,3})%")

        /** Address bar: ports and localhost -> http://localhost, LAN IPs -> http, words -> search, else https. */
        @JvmStatic fun normalise(raw: String): String {
            val u = raw.trim()
            return when {
                Regex(":?\\d{2,5}(/.*)?").matches(u) -> "http://localhost:" + u.removePrefix(":")
                Regex("(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d+)?([/?#].*)?", RegexOption.IGNORE_CASE).matches(u) -> "http://$u"
                Regex("(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?([/?#].*)?").matches(u) -> "http://$u"
                Regex("https?://\\S+", RegexOption.IGNORE_CASE).matches(u) -> u
                Regex("[a-zA-Z][a-zA-Z0-9+.-]*:.*").matches(u) && !Regex("[^\\s/:]+\\.[^\\s/:]+:\\d+.*").matches(u) -> search(u)
                ' ' in u || '.' !in u || u.startsWith(".") || u.endsWith(".") -> search(u)
                else -> "https://$u"
            }
        }
        private fun search(q: String) = "https://duckduckgo.com/?q=" + android.net.Uri.encode(q)
        /** Unit tests only: build the UI without starting Linux or the control socket. Not reachable from intents. */
        @VisibleForTesting @JvmStatic var testMode = false
        /** How many times start() was asked to boot Linux while testMode was on. */
        @VisibleForTesting @JvmStatic var testStarts = 0
        private val LCD = intArrayOf(Color.rgb(0x1E, 0x3B, 0x1A), Color.rgb(0x3D, 0x6B, 0x2F), Color.rgb(0x5E, 0x84, 0x30), Color.rgb(0xC4, 0xD8, 0x8A))
        private const val DEFAULT_BOOKMARKS = "Local server :8000|http://localhost:8000\nFlask app :5000|http://localhost:5000\n" +
            "gpiozero docs|https://gpiozero.readthedocs.io/\nPython tutorial|https://docs.python.org/3/tutorial/\nDebian packages|https://packages.debian.org/bookworm/"
        const val START_PAGE = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#1b1e22;color:#e8eaed;padding:18px;line-height:1.55;margin:0}code{background:#25292e;padding:2px 6px;border-radius:6px;color:#4cd964}
h2{margin:4px 0 8px;font-size:22px}.chip{display:inline-block;border:1px solid #2e6b3a;color:#4cd964;background:#16331e;border-radius:10px;padding:3px 10px;font-size:13px}
.card{background:#25292e;border-radius:14px;padding:12px 14px;margin:12px 0}</style></head>
<body><span class="chip">PiSandbox browser</span><h2>Browse the web or your own servers</h2>
<div class="card">Run a web server inside Linux:<br><code>python3 -m http.server 8000 &amp;</code><br>then <code>browse 8000</code> or type <code>8000</code> above.</div>
<div class="card">Flask: <code>sudo apt install python3-flask</code>, run your app on port 5000, then <code>browse 5000</code>.</div>
<div class="card">Text browser in the terminal: <code>sudo apt install w3m</code></div></body></html>"""
    }
}
