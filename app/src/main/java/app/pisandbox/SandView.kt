package app.pisandbox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Startup animation: the app icon's green chip outline is an empty sandbox; sand pours in from the top and
 * fills it, then the pink pi is drawn into the sand and the name fades in (~2 s, then the activity fades it out).
 *
 * All drawing is a pure function of the animation time ([drawAt]), so tests can render exact frames (the
 * splash preview GIF is made that way). Two modes:
 *  - intro: time-based, [INTRO_END] seconds;
 *  - setup (first run): the fill level follows the unpack percentage ("Setting up Linux… 42%"), sand keeps
 *    pouring, and the pi/title finale plays once [finishSetup] is called.
 * With animations turned off in Android settings ([reduceMotion]) it draws static frames only.
 */
class SandView(context: Context) : View(context) {
    var reduceMotion = false
    /** Called once when the intro (or the setup finale) has finished playing. */
    var onIntroDone: (() -> Unit)? = null
    var caption: String = ""; set(v) { field = v; invalidate() }

    private var start = -1L
    private var setupProgress = -1f          // <0: intro mode; 0..1: first-run setup
    private var shownLevel = 0f
    private var finaleAt = -1f               // setup mode: time the finale started
    private var doneFired = false
    var running = true; private set

    // ---- geometry (icon viewport units: chip box 28..80 incl. pins; package 34..74; see ic_launcher_foreground.xml)
    private var k = 1f; private var ox = 0f; private var oy = 0f
    private val pkg = RectF(); private val inner = RectF()
    private val piPath = Path(); private val piSeg = Path(); private var piLen = 0f
    private val piMeasure = PathMeasure()
    private val sandPath = Path(); private val clip = Path()
    private val grains = FloatArray(GRAINS * 2); private val grainPts = FloatArray(GRAINS * 2)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sand = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val d = resources.displayMetrics.density

    init {
        setBackgroundColor(Ui.BG)
        isClickable = true
        contentDescription = "PiSandbox is starting. Tap to skip."
        // fixed pseudo-random sand texture (deterministic: frames are reproducible)
        var s = 0x2545F491L
        fun rnd(): Float { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF }
        for (i in 0 until GRAINS) { grains[i * 2] = rnd(); grains[i * 2 + 1] = rnd() }
    }

    fun startIntro() { start = SystemClock.uptimeMillis(); running = true; doneFired = false; invalidate() }
    /** First-run mode: [p] 0..1 drives the fill level. */
    fun setSetupProgress(p: Float) {
        if (!inSetup) shownLevel = if (start < 0) 0f else introFrame(now()).level // continue from what is on screen
        setupProgress = p.coerceIn(0f, 1f); invalidate()
    }
    val inSetup get() = setupProgress >= 0f
    fun finishSetup() { if (inSetup && finaleAt < 0) { setupProgress = 1f; finaleAt = now() }; invalidate() }
    fun stop() { running = false }

    private fun now() = if (start < 0) 0f else (SystemClock.uptimeMillis() - start) / 1000f

    override fun onDraw(c: Canvas) {
        if (start < 0) start = SystemClock.uptimeMillis()
        val t = now()
        val f = if (inSetup) setupFrame(t) else introFrame(if (reduceMotion) INTRO_END else t)
        drawFrame(c, width, height, f)
        val finished = if (inSetup) finaleAt >= 0 && (reduceMotion || t - finaleAt >= FINALE) else reduceMotion || t >= INTRO_END
        if (finished && !doneFired) { doneFired = true; post { onIntroDone?.invoke() } }
        if (running && !reduceMotion && !(finished && !inSetup)) postInvalidateOnAnimation()
    }

    /** Animation state for one frame. */
    class Frame(val time: Float, val chip: Float, val level: Float, val stream: Float, val pi: Float, val title: Float,
                val caption: String, val progress: Float, val hint: Float)

    /** Intro timeline (seconds): chip appears 0-0.3, sand pours 0.25-1.5, pi drawn 1.15-1.75, title 1.35-1.9. */
    fun introFrame(t: Float): Frame {
        val level = ease(range(t, 0.3f, 1.55f))
        val stream = if (reduceMotion) 0f else min(range(t, 0.2f, 0.35f), 1f - range(t, 1.4f, 1.6f))
        return Frame(t, ease(range(t, 0f, 0.3f)), level, stream, ease(range(t, 1.15f, 1.75f)), range(t, 1.35f, 1.9f),
            caption, -1f, if (reduceMotion) 0f else 0.55f * range(t, 0.5f, 0.9f))
    }

    /** Setup timeline: level follows progress (eased toward it), stream on until the finale, then pi + title. */
    private fun setupFrame(t: Float): Frame {
        val target = 0.06f + 0.86f * setupProgress
        shownLevel = if (reduceMotion) target else shownLevel + (target - shownLevel) * 0.08f
        val ft = if (finaleAt < 0) -1f else t - finaleAt
        val level = if (ft < 0) shownLevel else shownLevel + (1f - shownLevel) * ease(range(ft, 0f, 0.45f))
        val stream = if (reduceMotion) 0f else if (ft < 0) min(1f, t / 0.3f) else 1f - range(ft, 0.2f, 0.45f)
        return Frame(t, if (reduceMotion) 1f else ease(range(t, 0f, 0.3f)), level, stream,
            if (ft < 0) 0f else ease(range(ft, 0.2f, 0.8f)), if (ft < 0) 0.85f else 1f, caption, setupProgress, 0f)
    }

    /** Draws one complete frame into [c] (size [w] x [h]). Pure: depends only on its arguments. */
    fun drawFrame(c: Canvas, w: Int, h: Int, f: Frame) {
        if (w == 0 || h == 0) return
        layout(w, h)
        c.drawColor(Ui.BG)
        val cx = ox + 54 * k
        // soft glow behind the chip
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb((26 * f.chip).toInt(), 0x4C, 0xD9, 0x64)
        c.drawCircle(cx, oy + 54 * k, 34 * k * (0.9f + 0.1f * f.chip), paint)

        c.save()
        val sc = 0.9f + 0.1f * f.chip
        c.scale(sc, sc, cx, oy + 54 * k)
        val a = (255 * f.chip).toInt()
        // pins
        paint.color = Ui.GREEN; paint.alpha = a
        for (p in PINS) {
            val x = p[0]; val y = p[1]; val horiz = p[2] == 1f
            val wv = if (horiz) 6f else 4f; val hv = if (horiz) 4f else 6f
            c.drawRoundRect(ox + x * k, oy + y * k, ox + (x + wv) * k, oy + (y + hv) * k, 0.5f * k, 0.5f * k, paint)
        }
        // package body (dark) then sand, clipped to the inside
        paint.color = Color.rgb(0x37, 0x47, 0x4F); paint.alpha = a
        c.drawRoundRect(pkg, 4 * k, 4 * k, paint)
        if (f.level > 0.001f) drawSand(c, f, cx)
        // outline on top so the sand edge is crisp
        stroke.shader = null; stroke.color = Ui.GREEN; stroke.alpha = a; stroke.strokeWidth = 2 * k
        c.drawRoundRect(pkg, 4 * k, 4 * k, stroke)
        // pink pi traced into the sand
        if (f.pi > 0f) {
            piSeg.reset(); piMeasure.setPath(piPath, false)
            var remaining = f.pi * piLen
            do {
                val len = piMeasure.length
                if (remaining > 0) piMeasure.getSegment(0f, min(len, remaining), piSeg, true)
                remaining -= len
            } while (piMeasure.nextContour())
            stroke.strokeWidth = 4.5f * k
            stroke.color = Color.argb(70, 0, 0, 0); c.save(); c.translate(0f, 0.8f * k); c.drawPath(piSeg, stroke); c.restore()
            stroke.color = Ui.ACCENT; c.drawPath(piSeg, stroke)
        }
        c.restore()
        if (f.stream > 0f) drawStream(c, f, cx)

        // texts
        val base = oy + 88 * k
        textP.typeface = Typeface.DEFAULT_BOLD; textP.textSize = 30 * d; textP.color = Ui.TEXT; textP.alpha = (255 * f.title).toInt()
        c.drawText("PiSandbox", cx, base + 30 * d, textP)
        textP.typeface = Typeface.DEFAULT; textP.textSize = 13 * d; textP.color = Ui.TEXT2; textP.alpha = (255 * f.title).toInt()
        c.drawText("Debian 12 • arm64 • GPIO playground", cx, base + 54 * d, textP)
        if (f.caption.isNotEmpty()) {
            textP.textSize = 13 * d; textP.color = Ui.GREEN; textP.alpha = 255
            c.drawText(f.caption, cx, base + 88 * d, textP)
        }
        if (f.progress >= 0f) {
            val bw = min(w * 0.6f, 240 * d); val by = base + 102 * d
            paint.shader = null; paint.color = Ui.SURFACE2
            c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + 4 * d, 2 * d, 2 * d, paint)
            paint.color = SAND_TOP
            c.drawRoundRect(cx - bw / 2, by, cx - bw / 2 + bw * f.progress, by + 4 * d, 2 * d, 2 * d, paint)
        }
        if (f.hint > 0f) {
            textP.textSize = 12 * d; textP.color = Ui.TEXT2; textP.alpha = (255 * f.hint).toInt()
            c.drawText("Tap to skip", cx, h - 40 * d, textP)
        }
    }

    private fun drawSand(c: Canvas, f: Frame, cx: Float) {
        val top = inner.bottom - f.level * inner.height()
        // a small heap where the stream lands, flattening as the box fills
        val heap = 5.5f * k * f.stream * (1f - 0.6f * f.level)
        val wob = 0.5f * k * f.stream
        sandPath.reset()
        sandPath.moveTo(inner.left - 2, inner.bottom + 2)
        val n = 28
        for (i in 0..n) {
            val x = inner.left + inner.width() * i / n
            val u = (x - cx) / (inner.width() * 0.32f)
            val y = top - heap * exp(-u * u) + wob * sin(i * 1.7f + f.time * 9f)
            sandPath.lineTo(x, max(y, inner.top - 6 * k))
        }
        sandPath.lineTo(inner.right + 2, inner.bottom + 2); sandPath.close()
        c.save(); c.clipPath(clip)
        c.drawPath(sandPath, sand)
        // texture: darker grains below the surface
        var m = 0
        for (i in 0 until GRAINS) {
            val gx = inner.left + grains[i * 2] * inner.width(); val gy = inner.top + grains[i * 2 + 1] * inner.height()
            if (gy > top + 1.2f * k) { grainPts[m++] = gx; grainPts[m++] = gy }
        }
        paint.shader = null; paint.color = SAND_GRAIN; paint.strokeWidth = 0.9f * k; paint.strokeCap = Paint.Cap.ROUND
        c.drawPoints(grainPts, 0, m, paint)
        c.restore()
    }

    private fun drawStream(c: Canvas, f: Frame, cx: Float) {
        val top = oy + 6 * k
        val surface = inner.bottom - f.level * inner.height() - 4f * k * (1f - 0.6f * f.level)
        val sw = 2.6f * k * f.stream
        paint.shader = null; paint.color = SAND_TOP; paint.alpha = (215 * f.stream).toInt()
        c.drawRoundRect(cx - sw / 2, top, cx + sw / 2, surface, sw / 2, sw / 2, paint)
        // falling grains around the stream
        paint.alpha = (255 * f.stream).toInt()
        for (i in 0 until 26) {
            val ph = frac(i * 0.618f + f.time * 1.9f)
            val y = top + ph * ph * (surface - top)
            val x = cx + (frac(i * 0.37f) - 0.5f) * sw * 2.4f + sin(f.time * 7 + i) * 0.3f * k
            c.drawCircle(x, y, (0.55f + 0.35f * frac(i * 0.73f)) * k, paint)
        }
        // splashes at the landing point
        for (j in 0 until 10) {
            val ph = frac(j * 0.29f + f.time * 2.3f)
            val dir = if (j % 2 == 0) 1 else -1
            val x = cx + dir * (0.3f + 0.7f * frac(j * 0.53f)) * 9f * k * ph
            val y = surface - sin(ph * PI.toFloat()) * 3.5f * k
            paint.alpha = (200 * f.stream * (1 - ph)).toInt()
            c.drawCircle(x, y, 0.6f * k, paint)
        }
    }

    private var lw = -1; private var lh = -1
    private fun layout(w: Int, h: Int) {
        if (w == lw && h == lh) return
        lw = w; lh = h
        val size = min(min(w, h) * 0.62f, 300 * d)       // drawn width of the 52-unit chip box
        k = size / 52f
        ox = w / 2f - 54 * k
        oy = h * 0.40f - 54 * k
        pkg.set(ox + 34 * k, oy + 34 * k, ox + 74 * k, oy + 74 * k)
        inner.set(pkg); inner.inset(1 * k, 1 * k)
        clip.reset(); clip.addRoundRect(inner, 3 * k, 3 * k, Path.Direction.CW)
        sand.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, SAND_TOP, SAND_BOTTOM, Shader.TileMode.CLAMP)
        // pi strokes from the icon: M42,46Q44,43 48,43H67 M49,43Q49,56 44,65 M60,43V60Q60,65 65,64
        fun X(v: Float) = ox + v * k
        fun Y(v: Float) = oy + v * k
        piPath.reset()
        piPath.moveTo(X(42f), Y(46f)); piPath.quadTo(X(44f), Y(43f), X(48f), Y(43f)); piPath.lineTo(X(67f), Y(43f))
        piPath.moveTo(X(49f), Y(43f)); piPath.quadTo(X(49f), Y(56f), X(44f), Y(65f))
        piPath.moveTo(X(60f), Y(43f)); piPath.lineTo(X(60f), Y(60f)); piPath.quadTo(X(60f), Y(65f), X(65f), Y(64f))
        piLen = 0f; piMeasure.setPath(piPath, false)
        do { piLen += piMeasure.length } while (piMeasure.nextContour())
    }

    companion object {
        const val INTRO_END = 2.0f
        const val FINALE = 1.0f
        private const val GRAINS = 140
        val SAND_TOP = Color.rgb(0xF1, 0xD0, 0x8A)
        val SAND_BOTTOM = Color.rgb(0xC9, 0x96, 0x4C)
        val SAND_GRAIN = Color.argb(150, 0xA8, 0x77, 0x36)
        /** pin rects (x, y, 1 = horizontal pin on the left/right side) in icon units */
        private val PINS = arrayOf(
            floatArrayOf(42f, 28f, 0f), floatArrayOf(52f, 28f, 0f), floatArrayOf(62f, 28f, 0f),
            floatArrayOf(42f, 74f, 0f), floatArrayOf(52f, 74f, 0f), floatArrayOf(62f, 74f, 0f),
            floatArrayOf(28f, 42f, 1f), floatArrayOf(28f, 52f, 1f), floatArrayOf(28f, 62f, 1f),
            floatArrayOf(74f, 42f, 1f), floatArrayOf(74f, 52f, 1f), floatArrayOf(74f, 62f, 1f))
        private fun range(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
        private fun ease(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
        private fun frac(x: Float) = x - kotlin.math.floor(x)
    }
}
