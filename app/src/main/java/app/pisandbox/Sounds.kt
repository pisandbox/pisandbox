package app.pisandbox

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tiny UI sounds, synthesised in code (no audio files in the APK): a soft "click" for button presses and a short
 * "tick" (three slightly different pitches) for terminal output. 16-bit mono PCM at 22.05 kHz, a few ms long.
 */
object Tones {
    const val RATE = 22050

    /** ~14 ms: a 1.9 kHz tone with a fast exponential decay plus a short noise transient at the start. */
    fun click(rate: Int = RATE): ShortArray {
        val n = rate * 14 / 1000
        val rnd = java.util.Random(7)
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = exp(-t / 0.0025)
            val tone = sin(2 * PI * 1900 * t) * 0.55
            val noise = if (i < rate / 1000) (rnd.nextDouble() * 2 - 1) * 0.35 * (1 - i / (rate / 1000.0)) else 0.0
            pcm((tone + noise) * env)
        }
    }

    /** ~9 ms "tk": a filtered noise burst with a low body (pitch varies by [variant] so runs of ticks don't sound robotic). */
    fun tick(variant: Int, rate: Int = RATE): ShortArray {
        val n = rate * 9 / 1000
        val rnd = java.util.Random(100L + variant)
        val body = doubleArrayOf(820.0, 960.0, 1100.0)[Math.floorMod(variant, 3)]
        var lp = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            lp += 0.45 * ((rnd.nextDouble() * 2 - 1) - lp)          // one-pole low-pass: softer, less hissy noise
            val env = exp(-t / 0.0018)
            pcm((lp * 0.7 + sin(2 * PI * body * t) * 0.3) * env * 0.8)
        }
    }

    private fun pcm(v: Double) = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort()

    /** RIFF/WAVE wrapper (PCM 16-bit mono) so SoundPool can load the generated samples from the cache dir. */
    fun wav(pcm: ShortArray, rate: Int = RATE): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}

/**
 * Decides when to play a typing tick while the terminal prints. Output itself is never delayed: the terminal shows
 * text as fast as it arrives and this only paces the sound.
 *  - at most one tick every [gapMs] (default 50 ms = 20 ticks/s, like quick typing);
 *  - ticking continues while output keeps arriving and stops [tailMs] after it stops;
 *  - a "burst" is output with no pause of [burstGapMs] or more; each burst gets at most [maxTicksPerBurst] ticks
 *    (about 2 s), so `cat bigfile`, `apt install` or a program printing in a tight loop goes quiet instead of
 *    rattling on. The next burst (after a pause) ticks again;
 *  - no tick within [echoQuietMs] of one of our own button clicks (key row, A/B, Run...), so a press never sounds
 *    twice (click + tick). Letters typed on the phone keyboard do tick: that's the typing sound.
 */
class TypingTicker(
    val gapMs: Long = 50, val tailMs: Long = 120, val burstGapMs: Long = 400,
    val maxTicksPerBurst: Int = 40, val echoQuietMs: Long = 80,
) {
    private var lastOutput = NEVER
    private var lastTick = NEVER
    private var lastInput = NEVER
    private var ticks = 0

    fun onOutput(now: Long) {
        if (now - lastInput < echoQuietMs) return       // the echo / immediate reply to our own button: already clicked
        if (now - lastOutput >= burstGapMs) ticks = 0
        lastOutput = now
    }

    fun onUserInput(now: Long) { lastInput = now }

    /** True while output is recent enough that the pump should keep polling. */
    fun active(now: Long) = now - lastOutput <= tailMs && ticks < maxTicksPerBurst

    /** How long the pump should wait before polling again (so ticks land on the 50 ms grid, not the frame grid). */
    fun nextDelay(now: Long) = (lastTick + gapMs - now).coerceIn(4, gapMs)

    /** Called by the pump; true = play one tick now. */
    fun poll(now: Long): Boolean {
        if (!active(now) || now - lastTick < gapMs || now - lastInput < echoQuietMs) return false
        lastTick = now; ticks++
        return true
    }

    companion object { private const val NEVER = Long.MIN_VALUE / 4 }
}

/**
 * Plays the UI sounds. Settings (SharedPreferences "pisandbox"): sound_clicks, sound_typing (both on by default) and
 * sound_volume 0-100 (default 30 = quiet). Uses USAGE_ASSISTANCE_SONIFICATION and plays nothing while the phone is
 * on silent or vibrate. Nothing is recorded or sent anywhere.
 */
class Sounds(private val ctx: Context, private val prefs: SharedPreferences) {
    var clicksOn = prefs.getBoolean(KEY_CLICKS, true); private set
    var typingOn = prefs.getBoolean(KEY_TYPING, true); private set
    var volume = prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100); private set
    val ticker = TypingTicker()

    /** How many clicks / ticks were actually played (tests). */
    @VisibleForTesting var clicksPlayed = 0; private set
    @VisibleForTesting var ticksPlayed = 0; private set

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private var pool: SoundPool? = null
    private var clickId = 0
    private val tickIds = IntArray(3)
    private var nextTick = 0

    fun setClicks(on: Boolean) { clicksOn = on; prefs.edit().putBoolean(KEY_CLICKS, on).apply() }
    fun setTyping(on: Boolean) { typingOn = on; prefs.edit().putBoolean(KEY_TYPING, on).apply() }
    fun setVolume(v: Int) { volume = v.coerceIn(0, 100); prefs.edit().putInt(KEY_VOLUME, volume).apply() }

    /** Silent / vibrate mode = no sounds at all. */
    fun muted() = audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL

    /** Perceptual curve: 30% on the slider = gain 0.16. */
    fun gain() = (volume / 100.0).pow(1.5).toFloat()

    /** A button was pressed. With our clicks off, fall back to Android's own touch sound (which follows the
     *  system "Touch sounds" setting) - our buttons have the system sound switched off to avoid double clicks. */
    fun click() {
        ticker.onUserInput(SystemClock.uptimeMillis())
        if (!clicksOn) { audio?.playSoundEffect(AudioManager.FX_KEY_CLICK); return }
        if (muted() || volume == 0) return
        clicksPlayed++
        ensurePool()?.play(clickId, gain(), gain(), 1, 0, 1f)
    }

    /** Preview at the current volume (volume slider released), even with click sounds off. */
    fun preview() {
        if (muted() || volume == 0) return
        clicksPlayed++
        ensurePool()?.play(clickId, gain(), gain(), 1, 0, 1f)
    }

    fun tick() {
        if (!typingOn || muted() || volume == 0) return
        ticksPlayed++
        val p = ensurePool() ?: return
        // ticks a bit quieter than clicks and slightly random in pitch/level: sounds like typing, not a metronome
        val g = gain() * (0.55f + 0.1f * nextTick)
        p.play(tickIds[nextTick], g, g, 0, 0, 0.97f + 0.03f * nextTick)
        nextTick = (nextTick + 1) % 3
    }

    private fun ensurePool(): SoundPool? {
        pool?.let { return it }
        return runCatching {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            val dir = File(ctx.cacheDir, "sounds").apply { mkdirs() }
            fun load(name: String, pcm: ShortArray): Int {
                val f = File(dir, "$name.wav"); val bytes = Tones.wav(pcm)
                if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
                return p.load(f.path, 1)
            }
            clickId = load("click", Tones.click())
            for (i in 0..2) tickIds[i] = load("tick$i", Tones.tick(i))
            pool = p; p
        }.getOrNull()
    }

    fun release() { pool?.release(); pool = null }

    companion object {
        const val KEY_CLICKS = "sound_clicks"
        const val KEY_TYPING = "sound_typing"
        const val KEY_VOLUME = "sound_volume"
        const val DEFAULT_VOLUME = 30
    }
}
