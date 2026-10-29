package app.pisandbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Synthesised UI sounds + the typing-tick pacing (plain JVM, no Android). */
class SoundsTest {
    private fun peak(s: ShortArray) = s.maxOf { abs(it.toInt()) } / 32767.0

    @Test fun tonesAreShortAudibleAndNotClipped() {
        val click = Tones.click(); val ticks = (0..2).map { Tones.tick(it) }
        assertTrue("click <= 20 ms", click.size <= Tones.RATE / 50)
        ticks.forEach { assertTrue("tick <= 12 ms", it.size <= Tones.RATE * 12 / 1000) }
        assertTrue(peak(click) in 0.3..0.95); ticks.forEach { assertTrue(peak(it) in 0.1..0.9) }
        // fades out: no pop at the end
        (ticks + listOf(click)).forEach { s -> assertTrue(s.takeLast(10).all { abs(it.toInt()) < 2000 }) }
        assertFalse("tick variants differ", ticks[0].contentEquals(ticks[1]))
        assertArrayEquals("deterministic", Tones.tick(1), Tones.tick(1))
    }

    @Test fun wavHeaderIsPcm16Mono() {
        val pcm = Tones.click(); val w = Tones.wav(pcm)
        assertEquals(44 + pcm.size * 2, w.size)
        assertTrue("tiny", w.size < 1024)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(1, b.getShort(20).toInt()); assertEquals(1, b.getShort(22).toInt()); assertEquals(Tones.RATE, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt()); assertEquals(pcm.size * 2, b.getInt(40)); assertEquals(pcm[5], b.getShort(44 + 10))
    }

    /** Drive the ticker like the 16 ms UI pump does; output arrives every [every] ms for [forMs]. */
    private fun run(t: TypingTicker, start: Long, forMs: Long, every: Long, tailMs: Long = 1000): Pair<List<Long>, Long> {
        val out = ArrayList<Long>(); var now = start
        while (now < start + forMs + tailMs) {
            if (now < start + forMs && (now - start) % every == 0L) t.onOutput(now)
            if (t.poll(now)) out += now
            now += 2
        }
        return out to now
    }

    @Test fun atMostOneTickPer50msAndStopsSoonAfterOutput() {
        val t = TypingTicker()
        val (ticks, _) = run(t, 0, 1000, 10)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a >= 50 })
        assertTrue("about 20/s: ${ticks.size}", ticks.size in 19..23)
        assertTrue("stops within ${t.tailMs} ms of the last output", ticks.last() <= 990 + t.tailMs)
    }

    @Test fun longBurstIsCappedAndAPauseResetsIt() {
        val t = TypingTicker()
        val (ticks, end) = run(t, 0, 10_000, 5)
        assertEquals(t.maxTicksPerBurst, ticks.size)
        assertTrue("cap reached in about 2 s", ticks.last() < 2500)
        val (again, _) = run(t, end + t.burstGapMs, 300, 5)
        assertTrue(again.isNotEmpty())
    }

    @Test fun singleLineGetsATickAndOwnButtonPressDoesNotDouble() {
        val t = TypingTicker()
        val (one, _) = run(t, 0, 1, 1)
        assertTrue("one line: a short flurry of 1-3 ticks, then quiet: $one", one.size in 1..3 && one.last() <= t.tailMs)
        val t2 = TypingTicker(); t2.onUserInput(10_000)
        val (echo, _) = run(t2, 10_000, 1, 1)
        assertTrue("echo of our own key press: quiet", echo.isEmpty())
    }
}
