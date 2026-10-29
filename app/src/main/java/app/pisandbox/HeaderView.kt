package app.pisandbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * On-screen virtual 40-pin header ("breadboard"). Two columns of pins (odd left, even right),
 * like the physical header. For each GPIO pin a part is drawn on the outside:
 *  - output   -> an LED that lights (brightness follows PWM duty). Green by default; programs can pick a
 *                colour (gpiozero TrafficLights -> red / amber / green). Three vertically adjacent LEDs
 *                coloured red, amber and green get a traffic-light housing;
 *  - input    -> a push button: touch and hold to press (connects to GND for pull-up pins,
 *                to 3V3 otherwise), multi-touch supported;
 *  - unused   -> a dim dot.
 * State comes from [GpioCore]; redraws on every change.
 */
@SuppressLint("ViewConstructor") // built in code only (needs the GpioCore), never inflated from XML
class HeaderView(ctx: Context, private val core: GpioCore) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val rowH = 34 * dp
    private val top = 8 * dp
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp; color = Color.rgb(0xDD, 0xE3, 0xEA) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * dp; color = Color.rgb(0x90, 0x9A, 0xA6) }
    private val touches = HashMap<Int, Int>() // pointerId -> bcm

    init { core.onChange = { postInvalidate() } }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (top * 2 + rowH * 20 + 24 * dp).toInt())

    private fun pinX(right: Boolean) = width / 2f + (if (right) 14 else -14) * dp
    private fun rowY(row: Int) = top + rowH * row + rowH / 2
    private fun partX(right: Boolean) = if (right) width - 26 * dp else 26 * dp

    private val r = RectF()   // reused: no allocations per frame

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(0x23, 0x27, 0x2E))
        // header strip
        p.color = Color.rgb(0x15, 0x17, 0x1B)
        r.set(width / 2f - 30 * dp, top - 2 * dp, width / 2f + 30 * dp, top + rowH * 20 + 2 * dp); c.drawRoundRect(r, 6 * dp, 6 * dp, p)
        val pins = core.snapshot()
        drawTrafficHousings(c, pins)
        for (row in 0 until 20) for (right in SIDES) {
            val phys = row * 2 + if (right) 2 else 1
            val y = rowY(row); val px = pinX(right)
            val bcm = PHYS_TO_BCM[phys]
            val power = POWER[phys]
            // pin pad
            p.color = when (power) { "GND" -> Color.rgb(0x60, 0x66, 0x70); "5V" -> Color.rgb(0xE0, 0x55, 0x55); "3V3" -> Color.rgb(0xF0, 0xA0, 0x40); else -> Color.rgb(0xC8, 0xA8, 0x50) }
            c.drawRect(px - 7 * dp, y - 7 * dp, px + 7 * dp, y + 7 * dp, p)
            small.textAlign = Paint.Align.CENTER
            small.color = Color.rgb(0x15, 0x17, 0x1B); c.drawText(phys.toString(), px, y + 3.5f * dp, small.apply { textSize = 8 * dp })
            small.textSize = 10 * dp; small.color = Color.rgb(0x90, 0x9A, 0xA6)
            // label
            val label = power ?: "GPIO$bcm"
            text.textAlign = if (right) Paint.Align.LEFT else Paint.Align.RIGHT
            val lx = if (right) px + 14 * dp else px - 14 * dp
            if (bcm == null) { text.color = Color.rgb(0x80, 0x88, 0x92); c.drawText(label, lx, y + 4 * dp, text); continue }
            val pin = pins[bcm]
            text.color = if (pin.mode == GpioCore.Mode.UNUSED) Color.rgb(0x9A, 0xA4, 0xB0) else Color.WHITE
            c.drawText(label, lx, y + 4 * dp, text)
            val sub = when (pin.mode) { GpioCore.Mode.OUT -> "out ${pin.level}"; GpioCore.Mode.PWM -> "pwm ${pin.duty.toInt()}%"
                GpioCore.Mode.IN -> "in ${core.value(pin)}" + if (pin.pull != GpioCore.Pull.OFF) " (${pin.pull.name.lowercase()})" else ""; else -> "" }
            small.textAlign = text.textAlign
            c.drawText(sub, if (right) partX(true) - 22 * dp else partX(false) + 22 * dp, y + 4 * dp, small.also { it.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT })
            // wire + part
            val qx = partX(right)
            when (pin.mode) {
                GpioCore.Mode.OUT, GpioCore.Mode.PWM -> {
                    val b = if (pin.mode == GpioCore.Mode.PWM) (pin.duty / 100.0).toFloat() else pin.level.toFloat()
                    val col = pin.color ?: GpioCore.GREEN
                    if (b > 0) { p.color = col; p.alpha = (90 * b).toInt(); c.drawCircle(qx, y, 15 * dp, p) }
                    p.color = Color.rgb(0x40, 0x44, 0x4C); p.alpha = 255; c.drawCircle(qx, y, 10 * dp, p)
                    p.color = col; p.alpha = (40 + 215 * b).toInt(); c.drawCircle(qx, y, 9 * dp, p); p.alpha = 255
                }
                GpioCore.Mode.IN -> {
                    p.color = Color.rgb(0x50, 0x55, 0x5E); r.set(qx - 13 * dp, y - 13 * dp, qx + 13 * dp, y + 13 * dp); c.drawRoundRect(r, 4 * dp, 4 * dp, p)
                    p.color = if (pin.pressed) Color.rgb(0x2B, 0xB3, 0xA3) else Color.rgb(0x18, 0x1A, 0x1E)
                    c.drawCircle(qx, y, if (pin.pressed) 7.5f * dp else 9 * dp, p)
                }
                else -> { p.color = Color.rgb(0x40, 0x44, 0x4C); c.drawCircle(qx, y, 3 * dp, p) }
            }
        }
        small.textAlign = Paint.Align.CENTER
        c.drawText("LEDs = outputs · hold a square button to press an input · BCM numbers", width / 2f, top + rowH * 20 + 16 * dp, small)
    }

    /** BCM pin drawn at [row] on the given side if it is a lit-able output, else null. */
    private fun outputAt(pins: List<GpioCore.Pin>, row: Int, right: Boolean): GpioCore.Pin? {
        val bcm = PHYS_TO_BCM[row * 2 + if (right) 2 else 1] ?: return null
        val p = pins[bcm]
        return p.takeIf { it.mode == GpioCore.Mode.OUT || it.mode == GpioCore.Mode.PWM }
    }

    /** A dark traffic-light body behind three adjacent LEDs (same column) coloured red, amber and green. */
    private fun drawTrafficHousings(c: Canvas, pins: List<GpioCore.Pin>) {
        for (right in SIDES) {
            var row = 0
            while (row <= 17) {
                val a = outputAt(pins, row, right)?.color; val b = outputAt(pins, row + 1, right)?.color
                val d = outputAt(pins, row + 2, right)?.color
                val isTraffic = a != null && b != null && d != null && a != b && b != d && a != d &&
                    isTrafficColor(a) && isTrafficColor(b) && isTrafficColor(d)
                if (!isTraffic) { row++; continue }
                val qx = partX(right)
                p.color = Color.rgb(0x11, 0x12, 0x14)
                r.set(qx - 17 * dp, rowY(row) - 17 * dp, qx + 17 * dp, rowY(row + 2) + 17 * dp)
                c.drawRoundRect(r, 9 * dp, 9 * dp, p)
                p.color = Color.rgb(0x3A, 0x3E, 0x46); p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp
                c.drawRoundRect(r, 9 * dp, 9 * dp, p); p.style = Paint.Style.FILL
                row += 3
            }
        }
    }

    private fun hit(x: Float, y: Float): Int? {
        val row = ((y - top) / rowH).toInt(); if (row !in 0 until 20) return null
        val phys = row * 2 + if (x > width / 2f) 2 else 1
        val bcm = PHYS_TO_BCM[phys] ?: return null
        return bcm.takeIf { core.pins[it].mode == GpioCore.Mode.IN }
    }

    /** Called when an input button is pressed (click sound). */
    var onPress: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex; val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val b = hit(e.getX(i), e.getY(i)) ?: return e.actionMasked != MotionEvent.ACTION_DOWN && touches.isNotEmpty()
                parent?.requestDisallowInterceptTouchEvent(true)
                touches[id] = b; core.setPressed(b, true); performHapticFeedback(1 /* VIRTUAL_KEY */); onPress?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touches.remove(id)?.let { b -> if (b !in touches.values) core.setPressed(b, false) }
            MotionEvent.ACTION_CANCEL -> { touches.values.toSet().forEach { core.setPressed(it, false) }; touches.clear() }
        }
        return true
    }

    companion object {
        private val SIDES = booleanArrayOf(false, true)
        private fun isTrafficColor(c: Int) = c == GpioCore.RED || c == GpioCore.AMBER || c == GpioCore.GREEN
        val PHYS_TO_BCM = mapOf(3 to 2, 5 to 3, 7 to 4, 8 to 14, 10 to 15, 11 to 17, 12 to 18, 13 to 27, 15 to 22, 16 to 23,
            18 to 24, 19 to 10, 21 to 9, 22 to 25, 23 to 11, 24 to 8, 26 to 7, 27 to 0, 28 to 1, 29 to 5, 31 to 6, 32 to 12,
            33 to 13, 35 to 19, 36 to 16, 37 to 26, 38 to 20, 40 to 21)
        val POWER = mapOf(1 to "3V3", 2 to "5V", 4 to "5V", 6 to "GND", 9 to "GND", 14 to "GND", 17 to "3V3", 20 to "GND",
            25 to "GND", 30 to "GND", 34 to "GND", 39 to "GND")
    }
}
