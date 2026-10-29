package app.pisandbox

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Virtual 40-pin header state + the line protocol the Linux-side shims (RPi.GPIO, gpiozero,
 * `gpio`, `browse`) speak over the control socket. Pure Kotlin/JVM (no Android classes) so it is
 * unit-tested on the build box, including against the real Python shims.
 *
 * Protocol: one request line -> one reply line ("ok [data]" | "err <message>"); the server may
 * also push "ev <bcm> <value>" lines for watched inputs at any time. BCM numbering, GPIO 0..27.
 *   hello <client>                 -> ok pisandbox <protocol> <revision>
 *   setup <bcm> out <0|1>          setup <bcm> in <off|up|down>      release <bcm>
 *   write <bcm> <0|1>              read <bcm> -> ok <0|1>            func <bcm> -> ok unused|in|out|pwm
 *   pwm <bcm> <freqHz> <duty0-100> pwmstop <bcm>
 *   watch <bcm> <rising|falling|both>   unwatch <bcm>
 *   press <bcm> <0|1>              (what the on-screen button does; used by tests)
 *   color <bcm> <red|amber|yellow|green|blue|white|#rrggbb|default>   color <bcm> -> ok <color>
 *   state -> ok <bcm>:<mode>:<pull>:<level>:<duty>:<pressed>:<color> ...      browse <url>
 * Virtual wiring: every output drives an LED (green unless a program picks a colour, e.g. gpiozero's
 * TrafficLights makes its pins red / amber / green; the colour resets when the pin is released); every input has a push button that connects the pin
 * to GND when its pull is "up" (reads 0 when pressed, gpiozero's default) and to 3V3 otherwise.
 * Like real hardware, pin state survives the program exiting; only release/cleanup resets a pin.
 */
class GpioCore {
    enum class Mode { UNUSED, IN, OUT, PWM }
    enum class Pull { OFF, UP, DOWN }
    class Pin(val bcm: Int) {
        var mode = Mode.UNUSED; var pull = Pull.OFF; var level = 0
        var duty = 0.0; var freq = 0.0; var pressed = false
        /** LED colour (ARGB) or null for the default green. */
        var color: Int? = null
    }

    companion object {
        const val GREEN = 0xFF43E07A.toInt()
        const val RED = 0xFFFF3B30.toInt()
        const val AMBER = 0xFFFFB000.toInt()
        val NAMED = linkedMapOf("red" to RED, "amber" to AMBER, "yellow" to AMBER, "green" to GREEN,
            "blue" to 0xFF4DA6FF.toInt(), "white" to 0xFFFFFFFF.toInt(), "orange" to 0xFFFF7A1A.toInt())

        /** "red", "#ff3b30", "default" -> ARGB (null = default green). */
        fun parseColor(s: String?): Int? {
            val v = s?.lowercase() ?: throw IllegalArgumentException("missing colour")
            if (v == "default" || v == "none") return null
            NAMED[v]?.let { return it }
            require(v.length == 7 && v[0] == '#' && v.drop(1).all { it in "0123456789abcdef" }) {
                "colour must be ${NAMED.keys.joinToString("|")}|#rrggbb|default" }
            return (0xFF000000.toInt()) or v.drop(1).toInt(16)
        }

        fun colorName(c: Int?): String = when (c) {
            null -> "default"
            else -> NAMED.entries.firstOrNull { it.value == c && it.key != "yellow" }?.key ?: "#%06x".format(c and 0xFFFFFF)
        }
    }

    val pins = Array(28) { Pin(it) }
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var onBrowse: ((String) -> Unit)? = null
    private val lock = Any()
    private val sessions = CopyOnWriteArrayList<Session>()

    inner class Session(private val out: OutputStream) {
        val watches = HashMap<Int, String>()
        fun send(line: String) = synchronized(this) {
            runCatching { out.write((line + "\n").toByteArray()); out.flush() }
        }
    }

    fun value(p: Pin): Int = when (p.mode) {
        Mode.OUT -> p.level
        Mode.PWM -> if (p.duty >= 50.0) 1 else 0
        else -> if (p.pressed) (if (p.pull == Pull.UP) 0 else 1) else (if (p.pull == Pull.UP) 1 else 0)
    }

    /** Runs [block] under the lock and pushes edge events for every pin whose value changed. */
    private fun <T> mutate(block: () -> T): T {
        val events = ArrayList<Pair<Int, Int>>()
        val r = synchronized(lock) {
            val before = IntArray(pins.size) { value(pins[it]) }
            val r = block()
            for (p in pins) { val v = value(p); if (v != before[p.bcm]) events += p.bcm to v }
            r
        }
        for ((bcm, v) in events) for (s in sessions) {
            val edge = synchronized(lock) { s.watches[bcm] } ?: continue
            if (edge == "both" || (edge == "rising" && v == 1) || (edge == "falling" && v == 0)) s.send("ev $bcm $v")
        }
        onChange?.invoke()
        return r
    }

    fun setPressed(bcm: Int, down: Boolean) { if (bcm in pins.indices) mutate { pins[bcm].pressed = down } }

    fun snapshot(): List<Pin> = synchronized(lock) {
        pins.map { o -> Pin(o.bcm).also { it.mode = o.mode; it.pull = o.pull; it.level = o.level; it.duty = o.duty; it.freq = o.freq; it.pressed = o.pressed; it.color = o.color } }
    }

    fun handle(s: Session?, line: String): String {
        val a = line.trim().split(Regex("\\s+"))
        if (a.isEmpty() || a[0].isEmpty()) return "err empty request"
        fun pin(i: Int = 1): Pin {
            val n = a.getOrNull(i)?.toIntOrNull() ?: throw IllegalArgumentException("missing pin number")
            require(n in pins.indices) { "invalid GPIO $n (BCM 0-27)" }
            return pins[n]
        }
        fun bit(i: Int) = when (a.getOrNull(i)) { "0" -> 0; "1" -> 1; else -> throw IllegalArgumentException("value must be 0 or 1") }
        return try {
            when (a[0]) {
                "hello" -> "ok pisandbox 1 c03115"
                "setup" -> { val p = pin(); mutate {
                    when (a.getOrNull(2)) {
                        "out" -> { p.mode = Mode.OUT; p.pull = Pull.OFF; p.level = bit(3); p.duty = 0.0 }
                        "in" -> { p.mode = Mode.IN; p.duty = 0.0; p.level = 0
                            p.pull = when (a.getOrNull(3) ?: "off") { "up" -> Pull.UP; "down" -> Pull.DOWN; "off" -> Pull.OFF
                                else -> throw IllegalArgumentException("pull must be off|up|down") } }
                        else -> throw IllegalArgumentException("direction must be in|out")
                    } }; "ok" }
                "release" -> { val p = pin(); mutate { p.mode = Mode.UNUSED; p.pull = Pull.OFF; p.level = 0; p.duty = 0.0; p.freq = 0.0; p.color = null }
                    if (s != null) synchronized(lock) { s.watches.remove(p.bcm) }; "ok" }
                "write" -> { val p = pin(); val v = bit(2); mutate {
                    require(p.mode == Mode.OUT) { "GPIO ${p.bcm} is not set up as an output" }; p.level = v }; "ok" }
                "read" -> { val p = pin(); "ok " + synchronized(lock) { value(p) } }
                "func" -> { val p = pin(); "ok " + synchronized(lock) { p.mode.name.lowercase() } }
                "pwm" -> { val p = pin(); val f = a.getOrNull(2)?.toDoubleOrNull(); val d = a.getOrNull(3)?.toDoubleOrNull()
                    require(f != null && f > 0) { "frequency must be > 0" }
                    require(d != null && d in 0.0..100.0) { "duty must be 0-100" }
                    mutate { require(p.mode == Mode.OUT || p.mode == Mode.PWM) { "GPIO ${p.bcm} is not set up as an output" }
                        p.mode = Mode.PWM; p.freq = f; p.duty = d }; "ok" }
                "pwmstop" -> { val p = pin(); mutate { if (p.mode == Mode.PWM) { p.mode = Mode.OUT; p.level = 0; p.duty = 0.0 } }; "ok" }
                "watch" -> { val p = pin(); val e = a.getOrNull(2) ?: "both"
                    require(e in setOf("rising", "falling", "both")) { "edge must be rising|falling|both" }
                    requireNotNull(s) { "no session" }; synchronized(lock) { s.watches[p.bcm] = e }; "ok" }
                "unwatch" -> { val p = pin(); if (s != null) synchronized(lock) { s.watches.remove(p.bcm) }; "ok" }
                "color" -> { val p = pin()
                    if (a.size < 3) "ok " + synchronized(lock) { colorName(p.color) }
                    else { val c = parseColor(a[2]); mutate { p.color = c }; "ok" } }
                "press" -> { val p = pin(); val v = bit(2); setPressed(p.bcm, v == 1); "ok" }
                "state" -> "ok " + synchronized(lock) { pins.joinToString(" ") {
                    "${it.bcm}:${it.mode.name.lowercase()}:${it.pull.name.lowercase()}:${it.level}:${it.duty}:${if (it.pressed) 1 else 0}:${colorName(it.color)}" } }
                "browse" -> { val url = line.trim().substringAfter(' ', "").trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) { "only http:// and https:// URLs" }
                    val cb = onBrowse ?: throw IllegalStateException("no browser available")
                    cb(url); "ok" }
                else -> "err unknown command '${a[0]}'"
            }
        } catch (e: Exception) { "err " + (e.message ?: e.toString()).replace('\n', ' ') }
    }

    /** Serves one connected client until EOF (blocking). Transport-agnostic (LocalSocket or TCP). */
    fun serve(input: InputStream, output: OutputStream) {
        val s = Session(output); sessions += s
        try {
            BufferedReader(InputStreamReader(input), 4096).use { r ->
                while (true) { val line = r.readLine() ?: break; if (line.isNotBlank()) s.send(handle(s, line)) }
            }
        } catch (_: Exception) {
        } finally { sessions -= s }
    }
}
