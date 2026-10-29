package app.pisandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * Renders every tab at Galaxy S25 size (1080x2340 px, 420 dpi = density 2.625, i.e. ~411.4x891 dp; laid out at 412 dp so the full 1080 px is covered) with a 24 dp status bar
 * and a 48 dp 3-button navigation bar taken off (worst case), saves PNGs to dist/screens/, and asserts that nothing
 * is clipped and no key / button label wraps or gets ellipsized.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class ScreensTest {
    private val outDir = File(System.getProperty("pisandbox.screensDir") ?: "build/screens").apply { mkdirs() }
    private var session: TerminalSession? = null

    @After fun tearDown() { session?.finishIfRunning(); Linux.session = null; Linux.host = null; MainActivity.testMode = false }

    private fun launch(): MainActivity {
        MainActivity.testMode = true
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        return a
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    /** Real terminal text needs the termux JNI pty code; tools/hostjni/libtermux.so is a host build of it (scripts/render-screens.sh). */
    private fun attachSampleSession(a: MainActivity) {
        val lib = System.getProperty("java.library.path", "").split(File.pathSeparator).map { File(it, "libtermux.so") }.firstOrNull { it.exists() } ?: File("libtermux.so")
        if (!lib.exists()) { println("No host libtermux.so in ${System.getProperty("java.library.path")} - terminal card left empty"); return }
        val g = "\\033[1;32m"; val b = "\\033[1;34m"; val r = "\\033[0m"
        val prompt = "${g}pi@pisandbox${r}:${b}~${r}\$ "
        // Sample transcript (text a real session prints), replayed through the real terminal emulator.
        val script = "printf '\\nPiSandbox 0.5.2 - Debian GNU/Linux 12 (bookworm) (arm64) in a PRoot sandbox on Android\\n" +
            "User \"pi\" has passwordless sudo.  Try:  sudo apt update   python3   pisandbox-fetch\\n" +
            "GPIO: open the Lessons or GPIO tab, or:  python3 ~/examples/blink.py   (stop: ^C key, or B in Game mode)\\n" +
            "Web:  browse <url>   e.g.  python3 -m http.server 8000 &  then  browse 8000\\n\\n" +
            "${prompt}ls examples\\nREADME.txt  blink.py  button.py  reaction_game.py  rpigpio_blink.py  traffic_lights.py\\n" +
            "${prompt}python3 examples/blink.py\\nBlinking GPIO17 - press B or ^C to stop\\n^C\\n" +
            "${prompt}uname -m\\naarch64\\n${prompt}'; exec sleep 60"
        val s = TerminalSession("/bin/sh", "/tmp", arrayOf("sh", "-c", script), arrayOf("TERM=xterm-256color", "PATH=/usr/bin:/bin"), 2000, a)
        session = s; a.attachTestSession(s); a.term.attachSession(s)
        a.window.decorView.requestLayout(); idle()
        Thread.sleep(700); idle(); a.term.onScreenUpdated(); idle()
    }

    private fun save(a: MainActivity, name: String): Bitmap {
        idle()
        val root = a.window.decorView
        val W = 1080; val H = 2340; val d = a.resources.displayMetrics.density
        val status = (24 * d).toInt(); val nav = (48 * d).toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Ui.BG)
        c.save(); c.translate(0f, status.toFloat()); c.clipRect(0, 0, W, H - status - nav); root.draw(c); c.restore()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d; isFakeBoldText = true }
        c.drawText("12:30", 20 * d, status - 7 * d, p)                                       // status bar (simulated)
        p.color = Color.argb(200, 255, 255, 255); p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d   // 3-button nav (simulated)
        val cy = H - nav / 2f
        c.drawRect(W * 0.25f - 8 * d, cy - 8 * d, W * 0.25f + 8 * d, cy + 8 * d, p)
        c.drawCircle(W / 2f, cy, 9 * d, p)
        c.drawLine(W * 0.75f + 5 * d, cy - 9 * d, W * 0.75f - 5 * d, cy, p); c.drawLine(W * 0.75f - 5 * d, cy, W * 0.75f + 5 * d, cy + 9 * d, p)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /** Centre (in a [save]d bitmap) of the LED drawn for physical header pin [phys]. */
    private fun ledCentre(a: MainActivity, phys: Int): Pair<Int, Int> {
        fun find(v: View): HeaderView? = v as? HeaderView ?: (v as? android.view.ViewGroup)?.let { g ->
            (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val hv = find(a.window.decorView)!!
        val d = a.resources.displayMetrics.density
        val loc = IntArray(2); hv.getLocationInWindow(loc)
        val row = (phys - 1) / 2; val right = phys % 2 == 0
        val x = loc[0] + (if (right) hv.width - 26 * d else 26 * d)
        val y = (24 * d).toInt() + loc[1] + 8 * d + 34 * d * row + 17 * d
        return x.toInt() to y.toInt()
    }

    /** Every visible, non-scrolling view lies inside the window; single-line labels neither wrap nor ellipsize. */
    private fun assertNoClipping(a: MainActivity, tag: String) {
        val root = a.window.decorView
        val win = Rect(0, 0, root.width, root.height)
        fun walk(v: View, scrolled: Boolean) {
            if (v.visibility != View.VISIBLE || v.width == 0) return
            val loc = IntArray(2); v.getLocationInWindow(loc)
            val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            if (!scrolled) assertTrue("$tag: ${describe(v)} $r outside window $win", win.contains(r))
            if (v is TextView && v.text.isNotEmpty() && v.maxLines == 1) {
                val l = v.layout
                if (l != null) {
                    assertEquals("$tag: '${v.text}' wraps", 1, l.lineCount)
                    assertEquals("$tag: '${v.text}' ellipsized", 0, l.getEllipsisCount(0))
                    assertTrue("$tag: '${v.text}' wider than its box (${l.getLineWidth(0)} > ${v.width - v.totalPaddingLeft - v.totalPaddingRight})",
                        l.getLineWidth(0) <= v.width - v.totalPaddingLeft - v.totalPaddingRight + 1)
                }
            }
            if (v is ViewGroup && v !is TerminalView && v !is WebView && !v.clipToOutline) {   // clipToOutline = intentionally cropped (round icon)
                val s = scrolled || v is ScrollView || v is AbsListView || v is HorizontalScrollView
                for (i in 0 until v.childCount) walk(v.getChildAt(i), s)
            }
        }
        walk(root, false)
    }

    private fun describe(v: View) = v.javaClass.simpleName + ((v as? TextView)?.text?.let { " '$it'" } ?: "") + (v.contentDescription?.let { " [$it]" } ?: "")

    private fun keysFitWithoutScrolling(a: MainActivity): Boolean {
        val hsv = findAll(a.window.decorView).filterIsInstance<HorizontalScrollView>().first { it.isShown && (it.getChildAt(0) as ViewGroup).childCount == 12 }
        val row = hsv.getChildAt(0) as ViewGroup
        for (i in 0 until row.childCount) {
            val k = row.getChildAt(i) as TextView
            assertEquals("key '${k.text}' wraps", 1, k.layout.lineCount)
            assertTrue("key '${k.text}' too narrow (${k.width}px)", k.width >= (20 * a.resources.displayMetrics.density).toInt())
            assertTrue("key '${k.text}' text clipped (${k.layout.getLineWidth(0)} in ${k.width}px, ${k.textSize}px font)", k.layout.getLineWidth(0) <= k.width - k.totalPaddingLeft - k.totalPaddingRight + 1)
        }
        return row.width <= hsv.width
    }

    private fun findAll(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { findAll(v.getChildAt(it)) } else emptyList()

    private fun seedHome(a: MainActivity) {
        val ex = File(a.filesDir, "rootfs/home/pi/examples"); ex.mkdirs()
        File(System.getProperty("pisandbox.examplesDir") ?: "../rootfs/runtime/usr/share/pisandbox/examples").listFiles()?.forEach { it.copyTo(File(ex, it.name), true) }
        File(a.filesDir, "rootfs/home/pi/projects").mkdirs()
        File(a.filesDir, "rootfs/home/pi/hello.py").writeText("print('Hello from PiSandbox!')\n")
        File(a.filesDir, "rootfs/home/pi/notes.txt").writeText("Ideas: a doorbell with Button(2) and Buzzer(18)\n")
    }

    @Test @Config(qualifiers = "w412dp-h819dp-port-420dpi")
    fun everyTabAtS25Size() {
        val a = launch()
        assertTrue("width ${a.window.decorView.width}", a.window.decorView.width >= 1080)
        seedHome(a)
        attachSampleSession(a)

        a.selectTab(MainActivity.Tab.TERMINAL); idle()
        assertTrue("extra-keys row must fit on one line without scrolling at 411dp", keysFitWithoutScrolling(a))
        assertNoClipping(a, "terminal"); save(a, "terminal")

        a.setGameMode(true); idle()
        assertTrue(keysFitWithoutScrolling(a)); assertNoClipping(a, "terminal-game"); save(a, "terminal-game-mode")
        a.setGameMode(false)

        a.setLcd(true); idle(); save(a, "terminal-lcd"); a.setLcd(false)

        a.selectTab(MainActivity.Tab.FILES); idle(); assertNoClipping(a, "files"); save(a, "files")
        a.openFile(File(a.filesDir, "rootfs/home/pi/examples/blink.py")); idle(); assertNoClipping(a, "viewer"); save(a, "files-viewer")
        a.onBackPressed(); idle()

        // Mission 3 (traffic_lights.py) as gpiozero leaves it: red GPIO17 / amber GPIO27 / green GPIO22
        Gpio.core.apply { handle(null, "setup 17 out 1"); handle(null, "setup 27 out 0"); handle(null, "setup 22 out 0"); handle(null, "setup 2 in up")
            handle(null, "color 17 red"); handle(null, "color 27 amber"); handle(null, "color 22 green") }
        a.selectTab(MainActivity.Tab.GPIO); idle(); assertNoClipping(a, "gpio"); save(a, "gpio")
        // all three lamps lit: each in its own colour, not all green (0.5.0 tester feedback)
        Gpio.core.apply { handle(null, "write 27 1"); handle(null, "write 22 1") }
        a.selectTab(MainActivity.Tab.GPIO); a.window.decorView.invalidate(); idle()
        val bmp = save(a, "traffic-colors")
        fun hsv(phys: Int): FloatArray { val (x, y) = ledCentre(a, phys); return FloatArray(3).also { Color.colorToHSV(bmp.getPixel(x, y), it) } }
        val red = hsv(11); val amber = hsv(13); val green = hsv(15)
        assertTrue("GPIO17 red: ${red.toList()}", (red[0] < 15 || red[0] > 345) && red[1] > 0.6f && red[2] > 0.8f)
        assertTrue("GPIO27 amber: ${amber.toList()}", amber[0] in 30f..50f && amber[1] > 0.6f && amber[2] > 0.8f)
        assertTrue("GPIO22 green: ${green.toList()}", green[0] in 120f..160f && green[1] > 0.5f && green[2] > 0.7f)
        Gpio.core.apply { for (p in listOf(17, 27, 22, 2)) handle(null, "release $p") }

        a.selectTab(MainActivity.Tab.BROWSER); idle(); assertNoClipping(a, "browser"); save(a, "browser")
        a.selectTab(MainActivity.Tab.LESSONS); idle(); assertNoClipping(a, "lessons"); save(a, "lessons")
        a.selectTab(MainActivity.Tab.SETTINGS); idle(); assertNoClipping(a, "settings"); save(a, "settings")
        a.showAbout(); idle(); assertNoClipping(a, "about"); save(a, "about")
        a.showLegal("All third-party notices", About.NOTICES); idle(); save(a, "about-notices")
        a.aboutBack(); a.aboutBack(); idle()

        // startup error screen (friendly, with Try again / Copy details)
        a.onPrepared(Result.failure(NotEnoughStorageException(RootfsInstaller.NEEDED_BYTES, 180L * 1024 * 1024))); idle()
        assertNoClipping(a, "error"); save(a, "error-storage")
    }

    /** Small phone (360x640dp after bars): still no wrapping/clipping; the keys row may scroll sideways. */
    @Test @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun smallPhoneNoClipping() {
        val a = launch()
        for (t in MainActivity.Tab.values()) { a.selectTab(t); idle(); assertNoClipping(a, "360dp ${t.label}") }
        a.selectTab(MainActivity.Tab.TERMINAL); idle(); println("360dp keys fit without scrolling: " + keysFitWithoutScrolling(a))
        a.setGameMode(true); idle(); assertNoClipping(a, "360dp game")
    }
}
