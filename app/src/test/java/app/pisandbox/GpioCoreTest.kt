package app.pisandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GpioCoreTest {
    @Test fun outputsAndErrors() {
        val c = GpioCore()
        assertEquals("ok pisandbox 1 c03115", c.handle(null, "hello"))
        assertTrue(c.handle(null, "write 17 1").startsWith("err"))
        assertEquals("ok", c.handle(null, "setup 17 out 0"))
        assertEquals("ok", c.handle(null, "write 17 1"))
        assertEquals("ok 1", c.handle(null, "read 17"))
        assertEquals("ok out", c.handle(null, "func 17"))
        assertTrue(c.handle(null, "setup 40 out").startsWith("err invalid GPIO"))
        assertTrue(c.handle(null, "bogus").startsWith("err unknown"))
        assertEquals("ok", c.handle(null, "pwm 17 100 25"))
        assertEquals(GpioCore.Mode.PWM, c.snapshot()[17].mode)
        assertEquals("ok", c.handle(null, "pwmstop 17"))
        assertEquals("ok", c.handle(null, "release 17"))
        assertEquals(GpioCore.Mode.UNUSED, c.snapshot()[17].mode)
    }

    @Test fun inputsPullsAndEvents() {
        val c = GpioCore()
        var changes = 0; c.onChange = { changes++ }
        c.handle(null, "setup 2 in up")
        assertEquals("ok 1", c.handle(null, "read 2"))
        // a client that watches falling edges receives "ev 2 0" when the on-screen button is pressed
        val out = ByteArrayOutputStream()
        val pin = PipedOutputStream(); val pout = PipedInputStream(pin)
        val t = thread { c.serve(pout, out) }
        pin.write("watch 2 falling\n".toByteArray()); pin.flush()
        val deadline = System.currentTimeMillis() + 2000
        while (!out.toString().contains("ok") && System.currentTimeMillis() < deadline) Thread.sleep(10)
        c.setPressed(2, true)
        assertEquals("ok 0", c.handle(null, "read 2"))
        c.setPressed(2, false)
        pin.close(); t.join(2000)
        assertEquals("ok\nev 2 0\n", out.toString())
        assertTrue(changes >= 3)
        c.handle(null, "setup 3 in down"); c.setPressed(3, true)
        assertEquals("ok 1", c.handle(null, "read 3"))
    }

    @Test fun ledColors() {
        val c = GpioCore()
        assertEquals("ok default", c.handle(null, "color 17"))
        assertEquals(null, c.snapshot()[17].color)
        assertEquals("ok", c.handle(null, "setup 17 out 1"))
        assertEquals("ok", c.handle(null, "color 17 red"))
        assertEquals("ok red", c.handle(null, "color 17"))
        assertEquals(GpioCore.RED, c.snapshot()[17].color)
        assertEquals("ok", c.handle(null, "color 27 YELLOW"))          // alias, case-insensitive
        assertEquals("ok amber", c.handle(null, "color 27"))
        assertEquals("ok", c.handle(null, "color 22 #43E07A"))
        assertEquals("ok green", c.handle(null, "color 22"))
        assertEquals("ok", c.handle(null, "color 5 #123abc"))
        assertEquals(0xFF123ABC.toInt(), c.snapshot()[5].color)
        assertTrue(c.handle(null, "state").contains(" 17:out:off:1:0.0:0:red "))
        assertTrue(c.handle(null, "state").contains(" 4:unused:off:0:0.0:0:default "))
        assertTrue(c.handle(null, "color 17 purple").startsWith("err colour must be"))
        assertTrue(c.handle(null, "color 17 #12345").startsWith("err"))
        assertTrue(c.handle(null, "color 99 red").startsWith("err invalid GPIO"))
        assertEquals("ok", c.handle(null, "setup 17 out 0"))           // re-setup keeps the colour
        assertEquals("ok red", c.handle(null, "color 17"))
        assertEquals("ok", c.handle(null, "release 17"))               // release resets it
        assertEquals("ok default", c.handle(null, "color 17"))
        assertEquals("ok", c.handle(null, "color 22 default"))
        assertEquals(null, c.snapshot()[22].color)
    }

    @Test fun browseOnlyHttp() {
        val c = GpioCore(); var got: String? = null
        assertTrue(c.handle(null, "browse http://x").startsWith("err no browser"))
        c.onBrowse = { got = it }
        assertTrue(c.handle(null, "browse file:///etc/passwd").startsWith("err"))
        assertTrue(c.handle(null, "browse javascript:alert(1)").startsWith("err"))
        assertEquals("ok", c.handle(null, "browse http://localhost:8000"))
        assertEquals("http://localhost:8000", got)
    }

    /**
     * End-to-end: the real guest-side code (RPi.GPIO shim, gpiozero + pisandbox pin factory,
     * `gpio` and `browse` commands) from the runtime tree, run by host python3 against THIS
     * Kotlin GpioCore over TCP (the app uses an abstract Unix socket; same line protocol).
     */
    private val root by lazy { generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "scripts/gpio-conformance.py").exists() } }
    private val tree by lazy { File(root, "build/runtime/tree") }

    /** Serve [core] on a loopback TCP port and start guest python code (from the runtime tree) against it. */
    private fun startGuestPython(core: GpioCore, vararg args: String): Pair<Process, ServerSocket> {
        assumeTrue("python3 + built runtime tree required", tree.isDirectory && File("/usr/bin/python3").exists())
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { while (true) { val s = try { ss.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) { s.use { core.serve(it.getInputStream(), it.getOutputStream()) } } } }
        val pb = ProcessBuilder("python3", "-u", *args).redirectErrorStream(true)
        pb.environment().apply {
            put("PISANDBOX_CTL", "tcp:127.0.0.1:${ss.localPort}")
            put("PYTHONPATH", File(tree, "usr/lib/python3/dist-packages").path)
            put("PATH", File(tree, "usr/local/bin").path + ":" + (get("PATH") ?: "/usr/bin:/bin"))
            put("GPIOZERO_PIN_FACTORY", "pisandbox")
        }
        return pb.start() to ss
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { assertTrue("timed out waiting for $what", System.currentTimeMillis() < end); Thread.sleep(10) }
    }

    @Test fun pythonShimsAgainstKotlinCore() {
        val core = GpioCore(); val browsed = mutableListOf<String>(); core.onBrowse = { synchronized(browsed) { browsed += it } }
        val (p, ss) = startGuestPython(core, File(root, "scripts/gpio-conformance.py").path)
        val log = p.inputStream.bufferedReader().readText()
        assertTrue("conformance timed out", p.waitFor(120, TimeUnit.SECONDS))
        ss.close()
        println(log)
        assertEquals("conformance failures:\n$log", 0, p.exitValue())
        assertTrue(log, log.contains("0 failure(s)"))
        assertEquals(listOf("http://localhost:8000"), synchronized(browsed) { browsed.toList() })
    }

    /** Mission 3 as shipped: red GPIO17 / amber GPIO27 / green GPIO22, coloured on the header; GPIO2 crosses. */
    @Test fun trafficLightsLessonLightsRedAmberGreen() {
        val core = GpioCore()
        val (p, ss) = startGuestPython(core, File(tree, "usr/share/pisandbox/examples/traffic_lights.py").path)
        val out = StringBuffer(); thread(isDaemon = true) { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        try {
            fun pin(b: Int) = core.snapshot()[b]
            waitFor("green on GPIO22") { pin(22).level == 1 && pin(2).mode == GpioCore.Mode.IN }
            assertEquals(GpioCore.RED, pin(17).color); assertEquals(GpioCore.AMBER, pin(27).color)
            assertEquals(GpioCore.GREEN, pin(22).color)
            assertEquals(listOf(0, 0, 1), listOf(17, 27, 22).map { pin(it).level })
            core.handle(null, "press 2 1"); Thread.sleep(100); core.handle(null, "press 2 0")
            waitFor("amber") { pin(27).level == 1 }
            assertEquals(listOf(0, 1, 0), listOf(17, 27, 22).map { pin(it).level })
            waitFor("red", 4_000) { pin(17).level == 1 }
            assertEquals(listOf(1, 0, 0), listOf(17, 27, 22).map { pin(it).level })
            waitFor("green again", 12_000) { pin(22).level == 1 }
            assertTrue(out.toString(), out.contains("WALK") && out.contains("GREEN again"))
        } finally { p.destroy(); p.waitFor(5, TimeUnit.SECONDS); ss.close() }
    }

    /** Mission 4 still plays: LED on GPIO17 (default green), five rounds, a best time. */
    @Test fun reactionGameStillWorks() {
        val core = GpioCore()
        val (p, ss) = startGuestPython(core, File(tree, "usr/share/pisandbox/examples/reaction_game.py").path)
        val out = StringBuffer(); val reader = thread(isDaemon = true) { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        try {
            repeat(5) { round ->
                waitFor("LED round ${round + 1}") { core.snapshot()[17].level == 1 }
                assertEquals(null, core.snapshot()[17].color)
                Thread.sleep(50)
                core.handle(null, "press 2 1")
                waitFor("LED off") { core.snapshot()[17].level == 0 }
                core.handle(null, "press 2 0")
            }
            assertTrue("game did not finish", p.waitFor(10, TimeUnit.SECONDS)); reader.join(2_000)
            assertEquals(out.toString(), 0, p.exitValue())
            assertTrue(out.toString(), Regex("Best: \\d+ ms").containsMatchIn(out))
        } finally { p.destroy(); ss.close() }
    }
    // ---- 0.5.2: examples stop cleanly on ^C / B, rpigpio_blink talks and blinks, false starts don't count ----

    private fun example(name: String) = File(tree, "usr/share/pisandbox/examples/$name").path

    /** Collects the guest's output in the background. */
    private class Out(p: Process) {
        val buf = StringBuffer()
        val reader = thread(isDaemon = true) { p.inputStream.bufferedReader().forEachLine { buf.append(it).append('\n') } }
        override fun toString() = buf.toString()
        fun count(s: String) = Regex(Regex.escape(s)).findAll(buf).count()
    }

    /** What the terminal's ^C (and the B key in Game mode / the GPIO tab's Stop button) does: SIGINT. */
    private fun ctrlC(p: Process) {
        // Process.pid() is Java 9+, not on the Android compile classpath; the test JVM (17) has it.
        val pid = Process::class.java.getMethod("pid").invoke(p) as Long
        assertEquals(0, ProcessBuilder("kill", "-INT", pid.toString()).start().waitFor())
    }

    private fun assertStopsCleanly(name: String, pins: List<Int>, ready: (GpioCore, Out) -> Boolean) {
        val core = GpioCore()
        val (p, ss) = startGuestPython(core, example(name))
        val out = Out(p)
        try {
            waitFor("$name running") { ready(core, out) }
            ctrlC(p)
            assertTrue("$name did not exit on ^C:\n$out", p.waitFor(15, TimeUnit.SECONDS)); out.reader.join(2_000)
            assertEquals("$name exit code\n$out", 0, p.exitValue())
            assertTrue("$name: no 'Stopped.'\n$out", out.toString().contains("Stopped."))
            assertFalse("$name printed a traceback\n$out", out.toString().contains("Traceback"))
            assertFalse("$name: KeyboardInterrupt leaked\n$out", out.toString().contains("KeyboardInterrupt"))
            for (b in pins) {
                val pin = core.snapshot()[b]
                assertEquals("$name left GPIO$b claimed", GpioCore.Mode.UNUSED, pin.mode)
                assertEquals("$name left GPIO$b on", 0, pin.level)
                assertEquals("$name left GPIO$b coloured", null, pin.color)
            }
        } finally { p.destroy(); ss.close() }
    }

    @Test fun blinkStopsCleanlyOnCtrlC() = assertStopsCleanly("blink.py", listOf(17)) { c, o ->
        c.snapshot()[17].mode == GpioCore.Mode.OUT && o.toString().contains("press B or ^C to stop") }

    @Test fun buttonStopsCleanlyOnCtrlC() = assertStopsCleanly("button.py", listOf(2, 17)) { c, o ->
        c.snapshot()[2].mode == GpioCore.Mode.IN && o.toString().contains("Press B or ^C") }

    @Test fun trafficLightsStopCleanlyOnCtrlC() = assertStopsCleanly("traffic_lights.py", listOf(2, 17, 22, 27)) { c, _ ->
        c.snapshot()[22].level == 1 && c.snapshot()[2].mode == GpioCore.Mode.IN }

    /** ^C while the LED is lit and the game is waiting for the press. */
    @Test fun reactionGameStopsCleanlyOnCtrlC() = assertStopsCleanly("reaction_game.py", listOf(2, 17)) { c, _ ->
        c.snapshot()[17].level == 1 }

    /** ^C in the middle of the PWM fade: the PWM is stopped and GPIO17 freed. */
    @Test fun rpigpioBlinkStopsCleanlyOnCtrlCDuringFade() = assertStopsCleanly("rpigpio_blink.py", listOf(17)) { c, _ ->
        c.snapshot()[17].mode == GpioCore.Mode.PWM }

    /** ^C straight after the start message (during the first blink). */
    @Test fun rpigpioBlinkStopsCleanlyOnEarlyCtrlC() = assertStopsCleanly("rpigpio_blink.py", listOf(17)) { _, o ->
        o.toString().contains("blink 1") }

    /** It says what it is doing before anything happens, really blinks GPIO17 three times, fades, and frees the pin. */
    @Test fun rpigpioBlinkAnnouncesItselfAndBlinks() {
        val core = GpioCore()
        val (p, ss) = startGuestPython(core, example("rpigpio_blink.py"))
        val out = Out(p)
        try {
            val levels = mutableListOf<Int>(); var sawPwm = false; var maxDuty = 0.0
            val end = System.currentTimeMillis() + 30_000
            while (p.isAlive && System.currentTimeMillis() < end) {
                val pin = core.snapshot()[17]
                if (pin.mode == GpioCore.Mode.OUT && levels.lastOrNull() != pin.level) levels += pin.level
                if (pin.mode == GpioCore.Mode.PWM) { sawPwm = true; maxDuty = maxOf(maxDuty, pin.duty) }
                Thread.sleep(10)
            }
            assertTrue("did not finish\n$out", p.waitFor(5, TimeUnit.SECONDS)); out.reader.join(2_000)
            assertEquals(out.toString(), 0, p.exitValue())
            val first = out.toString().lineSequence().first()
            assertTrue("no start message: '$first'", first.contains("blinking GPIO17") && first.contains("^C"))
            assertTrue("GPIO17 levels seen: $levels", levels.count { it == 1 } >= 3 && levels.count { it == 0 } >= 3)
            assertTrue("no PWM fade", sawPwm && maxDuty > 50.0)
            assertEquals((1..3).map { "blink $it" }, out.toString().lines().map { it.trim() }.filter { it.startsWith("blink") })
            assertTrue(out.toString(), out.toString().contains("Done."))
            assertEquals(GpioCore.Mode.UNUSED, core.snapshot()[17].mode)
        } finally { p.destroy(); ss.close() }
    }

    /**
     * A press before the LED lights is a false start: it is reported, the round is played again, and it never
     * becomes a time (0.5.1 printed "Too early" and then still recorded ~2000 ms). Holding the button after a
     * press only pauses the game until you let go.
     */
    @Test fun reactionGameFalseStartIsNotATime() {
        val core = GpioCore()
        val (p, ss) = startGuestPython(core, example("reaction_game.py"))
        val out = Out(p)
        try {
            // round 1: tap while it says "get ready" (LED off) -> false start, round 1 again
            waitFor("round 1") { out.count("Round 1: get ready") == 1 }
            assertEquals(0, core.snapshot()[17].level)
            core.handle(null, "press 2 1"); Thread.sleep(80); core.handle(null, "press 2 0")
            waitFor("false start reported") { out.count("False start") == 1 }
            waitFor("round 1 replayed") { out.count("Round 1: get ready") == 2 }
            // the replay is a fair round: LED, fast press
            waitFor("LED round 1") { core.snapshot()[17].level == 1 }
            Thread.sleep(40); core.handle(null, "press 2 1")
            waitFor("LED off") { core.snapshot()[17].level == 0 }
            // keep holding after the press: the game asks you to let go and does not start round 2 meanwhile
            waitFor("let go") { out.toString().contains("Let go of the button") }
            Thread.sleep(500)
            assertEquals(out.toString(), 0, out.count("Round 2: get ready"))
            assertEquals(0, core.snapshot()[17].level)
            core.handle(null, "press 2 0")
            // round 2: another early tap -> second false start, round 2 again
            waitFor("round 2") { out.count("Round 2: get ready") == 1 }
            core.handle(null, "press 2 1"); Thread.sleep(80)
            waitFor("second false start") { out.count("False start") == 2 }
            assertEquals("LED must not light on a false start", 0, core.snapshot()[17].level)
            core.handle(null, "press 2 0")
            waitFor("round 2 replayed") { out.count("Round 2: get ready") == 2 }
            // four more fair rounds, ~slow but not 2 s
            repeat(4) { r ->
                waitFor("LED round ${r + 2}") { core.snapshot()[17].level == 1 }
                Thread.sleep(40); core.handle(null, "press 2 1")
                waitFor("LED off") { core.snapshot()[17].level == 0 }
                core.handle(null, "press 2 0")
            }
            assertTrue("game did not finish\n$out", p.waitFor(15, TimeUnit.SECONDS)); out.reader.join(2_000)
            assertEquals(out.toString(), 0, p.exitValue())
            val times = Regex("^\\s+(\\d+) ms$", RegexOption.MULTILINE).findAll(out.toString()).map { it.groupValues[1].toInt() }.toList()
            assertEquals("exactly five timed rounds\n$out", 5, times.size)
            assertTrue("a false start was timed: $times\n$out", times.all { it < 1_000 })
            val best = Regex("Best: (\\d+) ms").find(out.toString())?.groupValues?.get(1)?.toInt()
            assertEquals(out.toString(), times.min(), best)
            assertTrue(out.toString(), out.toString().contains("false starts: 2"))
            assertFalse(out.toString(), out.toString().contains("Traceback"))
            assertEquals(GpioCore.Mode.UNUSED, core.snapshot()[17].mode)
        } finally { p.destroy(); ss.close() }
    }
}

