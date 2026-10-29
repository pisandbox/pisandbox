package app.pisandbox

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/** PiSandbox palette (dark, Material-like). Accent pink = the pi in the app icon; green = the chip. */
object Ui {
    val BG = Color.rgb(0x12, 0x14, 0x17)
    val SURFACE = Color.rgb(0x1B, 0x1E, 0x22)
    val SURFACE2 = Color.rgb(0x25, 0x29, 0x2E)
    val OUTLINE = Color.rgb(0x33, 0x38, 0x3F)
    val TEXT = Color.rgb(0xE8, 0xEA, 0xED)
    val TEXT2 = Color.rgb(0x9A, 0xA0, 0xA6)
    val ACCENT = Color.rgb(0xE2, 0x33, 0x7F)      // pink pi
    val GREEN = Color.rgb(0x4C, 0xD9, 0x64)       // chip green
    val GREEN_BG = Color.rgb(0x16, 0x33, 0x1E)
    val AMBER = Color.rgb(0xFF, 0xB0, 0x2E)
    val DANGER = Color.rgb(0xC6, 0x28, 0x4A)
    val TERM_BG = Color.rgb(0x0E, 0x10, 0x12)

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx; if (strokeColor != null) setStroke(strokePx, strokeColor)
    }
}

/** Simple outline icons drawn on a 24x24 grid (our own artwork, no icon font needed). */
class IconDrawable(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { TERMINAL, FILES, GPIO, BROWSER, LESSONS, SETTINGS, POWER, RESTART, BOOKMARK, STAR, HOME, BACK, FORWARD,
        RELOAD, LOCK, UP, PLAY, STOP, FILE, FOLDER, KEYBOARD, GAMEPAD, CLOSE, PLUS, SPEAKER }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color }
    var color: Int get() = p.color; set(v) { p.color = v; invalidateSelf() }
    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun draw(c: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()) / 24f
        c.save(); c.translate(b.centerX() - 12 * s, b.centerY() - 12 * s); c.scale(s, s)
        val path = Path()
        fun r(l: Float, t: Float, rr: Float, bb: Float, rad: Float = 2f) = c.drawRoundRect(RectF(l, t, rr, bb), rad, rad, p)
        fun line(vararg xy: Float) { path.reset(); path.moveTo(xy[0], xy[1]); var i = 2; while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }; c.drawPath(path, p) }
        when (kind) {
            Kind.TERMINAL -> { r(3f, 4f, 21f, 20f); line(7f, 9f, 10f, 12f, 7f, 15f); line(12f, 15f, 16f, 15f) }
            Kind.FILES, Kind.FOLDER -> line(3f, 6f, 3f, 19f, 21f, 19f, 21f, 8f, 11f, 8f, 9f, 5f, 3f, 5f, 3f, 6f)
            Kind.FILE -> { line(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, 6f, 3f); line(14f, 3f, 14f, 8f, 19f, 8f) }
            Kind.GPIO -> { r(7f, 7f, 17f, 17f, 1.5f); for (k in listOf(9.5f, 12f, 14.5f)) { line(k, 3f, k, 7f); line(k, 17f, k, 21f); line(3f, k, 7f, k); line(17f, k, 21f, k) } }
            Kind.BROWSER -> { c.drawCircle(12f, 12f, 9f, p); c.drawOval(RectF(8f, 3f, 16f, 21f), p); line(3f, 12f, 21f, 12f) }
            Kind.LESSONS -> { line(12f, 6f, 12f, 20f); line(12f, 6f, 9f, 4.5f, 3f, 4.5f, 3f, 18.5f, 9f, 18.5f, 12f, 20f, 15f, 18.5f, 21f, 18.5f, 21f, 4.5f, 15f, 4.5f, 12f, 6f) }
            Kind.SETTINGS -> { c.drawCircle(12f, 12f, 3f, p); c.drawCircle(12f, 12f, 7f, p)
                for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); line(12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(), 12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat()) } }
            Kind.POWER -> { c.drawArc(RectF(4f, 5f, 20f, 21f), -60f, 300f, false, p); line(12f, 3f, 12f, 12f) }
            Kind.RESTART, Kind.RELOAD -> { c.drawArc(RectF(4f, 4f, 20f, 20f), -40f, 300f, false, p); line(19.5f, 3.5f, 18.3f, 7.8f, 14f, 7f) }
            Kind.BOOKMARK -> line(6f, 3f, 18f, 3f, 18f, 21f, 12f, 16f, 6f, 21f, 6f, 3f)
            Kind.STAR -> { path.reset(); for (k in 0 until 10) { val rad = if (k % 2 == 0) 9.5f else 4f; val a = Math.toRadians(-90.0 + k * 36)
                val x = 12f + rad * Math.cos(a).toFloat(); val y = 12.5f + rad * Math.sin(a).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }; path.close(); c.drawPath(path, p) }
            Kind.HOME -> line(3f, 11f, 12f, 3f, 21f, 11f, 19f, 11f, 19f, 21f, 5f, 21f, 5f, 11f, 3f, 11f)
            Kind.BACK -> { line(20f, 12f, 4f, 12f); line(10f, 6f, 4f, 12f, 10f, 18f) }
            Kind.FORWARD -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f, 14f, 18f) }
            Kind.UP -> { line(12f, 20f, 12f, 4f); line(6f, 10f, 12f, 4f, 18f, 10f) }
            Kind.LOCK -> { r(5f, 11f, 19f, 21f); c.drawArc(RectF(8f, 4f, 16f, 14f), 180f, 180f, false, p); line(8f, 9f, 8f, 11f); line(16f, 9f, 16f, 11f) }
            Kind.PLAY -> { p.style = Paint.Style.FILL_AND_STROKE; line(7f, 4f, 19f, 12f, 7f, 20f, 7f, 4f); p.style = Paint.Style.STROKE }
            Kind.STOP -> { p.style = Paint.Style.FILL_AND_STROKE; r(6f, 6f, 18f, 18f, 1.5f); p.style = Paint.Style.STROKE }
            Kind.KEYBOARD -> { r(2f, 6f, 22f, 18f); for (y in listOf(9.5f, 12.5f)) for (x in listOf(6f, 9f, 12f, 15f, 18f)) c.drawPoint(x, y, p); line(8f, 15.5f, 16f, 15.5f) }
            Kind.GAMEPAD -> { r(2f, 7f, 22f, 18f, 5f); line(7f, 10.5f, 7f, 14.5f); line(5f, 12.5f, 9f, 12.5f); c.drawCircle(15.5f, 11f, 0.8f, p); c.drawCircle(18f, 13.5f, 0.8f, p) }
            Kind.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Kind.SPEAKER -> { line(4f, 9.5f, 8f, 9.5f, 13f, 5f, 13f, 19f, 8f, 14.5f, 4f, 14.5f, 4f, 9.5f)
                c.drawArc(RectF(11f, 8f, 19f, 16f), -50f, 100f, false, p); c.drawArc(RectF(10f, 5f, 22f, 19f), -50f, 100f, false, p) }
            Kind.PLUS -> { r(3f, 3f, 21f, 21f, 4f); line(12f, 8f, 12f, 16f); line(8f, 12f, 16f, 12f) }
        }
        c.restore()
    }
}
