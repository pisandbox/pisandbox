package app.pisandbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the sand splash frame by frame (the same drawFrame() the device uses) for the preview GIF
 * (scripts/render-splash.sh -> dist/screens/splash.gif), and checks the timeline: fits the 1.5-2.5 s budget,
 * sand level only rises, pi/title only appear at the end, reduce-motion shows a static final frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h891dp-port-420dpi")
class SplashTest {
    private val out = File(System.getProperty("pisandbox.framesDir") ?: "build/splash-frames")
    private val W = 1080; private val H = 2340
    private val FPS = 50                                      // GIF timing is in 1/100 s, so 50 fps previews accurately

    private fun view() = SandView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, W, H) }

    private fun save(dir: File, i: Int, bmp: Bitmap) {
        dir.mkdirs(); File(dir, "%04d.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun introTimeline() {
        val v = view()
        var lastLevel = -1f
        var t = 0f
        while (t <= SandView.INTRO_END) {
            val f = v.introFrame(t)
            assertTrue("level never drops", f.level >= lastLevel); lastLevel = f.level
            if (t < 1.1f) { assertEquals(0f, f.pi, 0f); assertEquals(0f, f.title, 0f) }
            t += 0.01f
        }
        assertEquals("full at the end", 1f, v.introFrame(SandView.INTRO_END).level, 1e-4f)
        assertEquals(1f, v.introFrame(SandView.INTRO_END).pi, 1e-4f)
        assertTrue("1.5-2.5 s incl. 0.3 s fade", SandView.INTRO_END + 0.3f in 1.5f..2.5f)
        v.reduceMotion = true
        assertEquals("no pouring stream with reduce motion", 0f, v.introFrame(SandView.INTRO_END).stream, 0f)
    }

    /** Normal start: intro 0..2.0 s, then 0.3 s cross-fade into the app (dist/screens/terminal.png if present). */
    @Test fun renderIntroFrames() {
        val v = view(); val dir = File(out, "intro").apply { deleteRecursively() }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        var i = 0
        val n = (SandView.INTRO_END * FPS).toInt()
        for (k in 0..n) { v.drawFrame(c, W, H, v.introFrame(k / FPS.toFloat())); save(dir, i++, bmp) }
        repeat(FPS / 4) { save(dir, i++, bmp) }               // hold (app finishing start-up)
        val app = File(System.getProperty("pisandbox.screensDir") ?: "", "terminal.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val last = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val fade = (0.3f * FPS).toInt()
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (k in 1..fade) {
            c.drawColor(Ui.BG)
            if (app != null) c.drawBitmap(app, null, Rect(0, 0, W, H), null)
            p.alpha = (255 * (1 - k / fade.toFloat())).toInt(); c.drawBitmap(last, 0f, 0f, p)
            save(dir, i++, bmp)
        }
        repeat(FPS) { save(dir, i++, bmp) }                   // show the app for 1 s before the GIF loops
        assertTrue(i > 100)
    }

    /** First run: "Setting up Linux… N%" while Debian unpacks, fill follows progress, finale when ready. */
    @Test fun renderFirstRunFrames() {
        val v = view(); val dir = File(out, "firstrun").apply { deleteRecursively() }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        var i = 0
        val secs = 3.2f                                       // the real unpack takes ~20-60 s; compressed for the preview
        val n = (secs * FPS).toInt()
        var shown = 0.06f
        for (k in 0..n) {
            val t = k / FPS.toFloat()
            val p = (t / secs).coerceIn(0f, 1f); val pct = (p * 100).toInt()
            shown += (0.06f + 0.86f * p - shown) * 0.08f
            val f = SandView.Frame(t, minOf(1f, t / 0.3f), shown, minOf(1f, t / 0.3f), 0f, 0.85f, "Setting up Linux… $pct%", p, 0f)
            v.drawFrame(c, W, H, f); save(dir, i++, bmp)
        }
        for (k in 0..FPS) {                                   // finale (1 s)
            val ft = k / FPS.toFloat()
            fun ease(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
            fun range(a: Float, b: Float) = ((ft - a) / (b - a)).coerceIn(0f, 1f)
            val f = SandView.Frame(secs + ft, 1f, shown + (1 - shown) * ease(range(0f, 0.45f)), 1 - range(0.2f, 0.45f),
                ease(range(0.2f, 0.8f)), 1f, "Linux is ready", 1f, 0f)
            v.drawFrame(c, W, H, f); save(dir, i++, bmp)
        }
        repeat(FPS / 2) { save(dir, i++, bmp) }
        assertTrue(i > 150)
    }
}
