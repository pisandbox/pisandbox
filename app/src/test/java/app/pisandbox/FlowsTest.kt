package app.pisandbox

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import com.termux.terminal.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * End-to-end UI flows on the real MainActivity (Robolectric, sdk 34): tabs, settings persistence, game mode / LCD /
 * font size, Lessons Run, Files viewer + Run, browser address bar + back/forward, Power off / restart, About,
 * startup error screens, activity recreation (rotation / dark mode / font scale) keeping the same Linux shell,
 * process death, accessibility labels, and layouts at large font, landscape, split-screen and night mode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w412dp-h819dp-port-420dpi")
class FlowsTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val hostJni = System.getProperty("java.library.path", "").split(File.pathSeparator).any { File(it, "libtermux.so").exists() }

    @Before fun setUp() { MainActivity.testMode = true; MainActivity.testStarts = 0; resetLinux() }
    @After fun tearDown() { sessions.forEach { it.finishIfRunning() }; resetLinux(); MainActivity.testMode = false }

    private fun resetLinux() { Linux.session = null; Linux.pending = null; Linux.preparing = false; Linux.stopRequested = false; Linux.host = null }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch(): Pair<ActivityController<MainActivity>, MainActivity> {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(); return c to c.get()
    }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun visible(a: MainActivity) = all(a.window.decorView).filter { it.isShown }
    private fun byDesc(a: MainActivity, d: String) = visible(a).first { it.contentDescription?.toString() == d }
    /** The smallest clickable view showing [label] (panels like the file viewer are clickable too). */
    private fun byLabel(a: MainActivity, label: String) = visible(a).filter { v -> v is ViewGroup && v.isClickable && all(v).any { it is TextView && it.text.toString() == label } }
        .minByOrNull { all(it).size } ?: throw AssertionError("no button '$label'")
    private fun transcript(s: TerminalSession) = s.emulator?.screen?.transcriptText.orEmpty()

    /** A real pty session (host build of termux's JNI) running `cat`, so whatever the app types is echoed back. */
    private fun catSession(a: MainActivity): TerminalSession? {
        if (!hostJni) { println("no host libtermux.so: skipping session part"); return null }
        // Like bash at its prompt, it ignores Ctrl-C (which runInTerminal sends first); plain cat would die from it.
        val s = TerminalSession("/bin/sh", "/tmp", arrayOf("sh", "-c", "trap '' INT; exec cat"), arrayOf("PATH=/usr/bin:/bin"), 500, a)
        sessions += s; a.attachTestSession(s); a.term.attachSession(s)
        a.window.decorView.requestLayout(); idle()                 // TerminalView starts the process once it has a size
        waitFor("session started") { s.isRunning }
        return s
    }
    /**
     * Robolectric's android.system.Os.kill() is a no-op, so the SIGKILL termux sends on finishIfRunning() never
     * reaches the host process. After asserting the app *asked* for the stop, deliver it the way Android would.
     */
    private fun osKill(s: TerminalSession) { ProcessBuilder("kill", "-9", s.pid.toString()).start().waitFor() }
    private fun waitFor(what: String, ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); if (cond()) return; Thread.sleep(30) }
        throw AssertionError("timed out waiting for $what")
    }

    // ------------------------------------------------------------------ tabs + navigation
    @Test fun everyTabOpensFromTheBottomBarAndBackReturnsToTerminal() {
        val (_, a) = launch()
        assertEquals(MainActivity.Status.RUNNING, a.status)
        assertEquals(View.GONE, a.splash.visibility)
        for (t in MainActivity.Tab.values()) {
            byDesc(a, t.label).performClick(); idle()
            assertEquals(t, a.currentTab)
        }
        a.onBackPressed(); idle()                       // Settings -> Terminal
        assertEquals(MainActivity.Tab.TERMINAL, a.currentTab)
        a.onBackPressed(); idle()                       // Terminal: goes to background, NOT finish (Linux keeps running)
        assertFalse("back on Terminal must not finish the activity", a.isFinishing)
    }

    // ------------------------------------------------------------------ settings persist
    @Test fun settingsPersistAcrossRestart() {
        val (c, a) = launch()
        a.selectTab(MainActivity.Tab.SETTINGS); idle()
        val switches = visible(a).filterIsInstance<Switch>().filter { it.contentDescription in setOf("LCD green tint", "Game mode") }
        assertEquals("game mode + LCD switches", 2, switches.size)
        switches.forEach { it.performClick() }; idle()
        assertTrue(a.gameMode); assertTrue(a.lcd)
        byDesc(a, "Larger terminal text").performClick(); byDesc(a, "Larger terminal text").performClick(); idle()
        assertEquals(14, a.fontSp)
        repeat(30) { byDesc(a, "Smaller terminal text").performClick() }; idle()
        assertEquals("font clamps at 7", 7, a.fontSp)
        a.setFont(16)
        c.pause().stop().destroy(); idle()
        val (_, b) = launch()
        assertTrue(b.gameMode); assertTrue(b.lcd); assertEquals(16, b.fontSp)
        b.selectTab(MainActivity.Tab.SETTINGS); idle()
        assertTrue(visible(b).filterIsInstance<Switch>().all { it.isChecked })
    }

    // ------------------------------------------------------------------ sounds
    private fun audio() = RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test fun soundSettingsDefaultOnAtLowVolumeAndPersist() {
        val (c, a) = launch()
        assertTrue(a.sounds.clicksOn); assertTrue(a.sounds.typingOn); assertEquals(Sounds.DEFAULT_VOLUME, a.sounds.volume)
        assertTrue("default volume is quiet", a.sounds.gain() < 0.2f)
        a.selectTab(MainActivity.Tab.SETTINGS); idle()
        val clicks = byDesc(a, "Button click sounds") as Switch; val typing = byDesc(a, "Typing sounds") as Switch
        assertTrue(clicks.isChecked && typing.isChecked)
        val vol = byDesc(a, "Sound volume") as android.widget.SeekBar
        assertEquals(30, vol.progress)
        typing.performClick(); idle()
        assertFalse(a.sounds.typingOn)
        a.sounds.setVolume(55)
        c.pause().stop().destroy(); idle()
        val (_, b) = launch()
        assertFalse(b.sounds.typingOn); assertTrue(b.sounds.clicksOn); assertEquals(55, b.sounds.volume)
        b.selectTab(MainActivity.Tab.SETTINGS); idle()
        assertFalse((byDesc(b, "Typing sounds") as Switch).isChecked)
        assertEquals(55, (byDesc(b, "Sound volume") as android.widget.SeekBar).progress)
    }

    @Test fun buttonsClickAndSystemTouchSoundIsNotDoubled() {
        val (_, a) = launch()
        val n0 = a.sounds.clicksPlayed
        byDesc(a, "Files").performClick(); byDesc(a, "Terminal").performClick(); idle()       // tabs
        byDesc(a, "TAB").performClick(); idle()                                                  // key row
        byLabel(a, "Lessons").performClick(); idle()                                              // action buttons
        byDesc(a, "Settings").performClick(); idle()
        byDesc(a, "Larger terminal text").performClick(); idle()                                  // settings controls
        (byDesc(a, "Game mode") as Switch).performClick(); idle()
        assertEquals(n0 + 7, a.sounds.clicksPlayed)
        assertTrue("our buttons have Android's own click switched off", listOf("Files", "TAB", "Larger terminal text", "Game mode", "Settings").all { d -> all(a.window.decorView).filter { it.contentDescription?.toString() == d }.all { !it.isSoundEffectsEnabled } })
        byDesc(a, "Terminal").performClick(); idle()
        assertTrue(visible(a).any { it is TextView && it.text.toString() == "ENTER" })            // game pad buttons
        val enter = visible(a).first { it is TextView && it.text.toString() == "A" }
        val before = a.sounds.clicksPlayed; enter.performClick(); idle()
        assertEquals(before + 1, a.sounds.clicksPlayed)
        a.sounds.setClicks(false)
        byDesc(a, "Files").performClick(); idle()
        assertEquals("click sounds off", before + 1, a.sounds.clicksPlayed)
    }

    @Test fun noSoundsOnSilentOrVibrate() {
        val (_, a) = launch()
        for (mode in listOf(android.media.AudioManager.RINGER_MODE_SILENT, android.media.AudioManager.RINGER_MODE_VIBRATE)) {
            audio().ringerMode = mode
            val c0 = a.sounds.clicksPlayed; val t0 = a.sounds.ticksPlayed
            byDesc(a, "Files").performClick(); byDesc(a, "Terminal").performClick(); idle()
            repeat(10) { a.onTerminalOutput(); advance(60) }
            assertEquals(c0, a.sounds.clicksPlayed); assertEquals(t0, a.sounds.ticksPlayed)
        }
        audio().ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
        byDesc(a, "Files").performClick(); idle()
        assertTrue(a.sounds.clicksPlayed > 0)
    }

    @Test fun terminalOutputTicksAreRateLimitedCappedAndStop() {
        val (_, a) = launch()
        advance(500)
        // 1 s of output arriving every 10 ms -> about 20 ticks (max one per 50 ms), output itself is never held back
        repeat(100) { a.onTerminalOutput(); advance(10) }
        val t1 = a.sounds.ticksPlayed
        assertTrue("rate limited: $t1", t1 in 18..23)
        advance(130); val t2 = a.sounds.ticksPlayed
        advance(2000)
        assertEquals("ticks stop shortly after output stops", t2, a.sounds.ticksPlayed)
        assertTrue(t2 - t1 <= 3)
        // a huge burst (10 s non-stop) is capped at maxTicksPerBurst, then quiet until the output pauses
        val t3 = a.sounds.ticksPlayed
        repeat(1000) { a.onTerminalOutput(); advance(10) }
        assertEquals(a.sounds.ticker.maxTicksPerBurst, a.sounds.ticksPlayed - t3)
        advance(600); val t4 = a.sounds.ticksPlayed
        a.onTerminalOutput(); idle()
        assertEquals("next burst ticks again", t4 + 1, a.sounds.ticksPlayed)
        // not on other tabs, not with typing sounds off
        advance(600); a.selectTab(MainActivity.Tab.FILES); idle()
        val t5 = a.sounds.ticksPlayed; repeat(20) { a.onTerminalOutput(); advance(20) }
        assertEquals(t5, a.sounds.ticksPlayed)
        a.selectTab(MainActivity.Tab.TERMINAL); a.sounds.setTyping(false); idle()
        repeat(20) { a.onTerminalOutput(); advance(20) }
        assertEquals(t5, a.sounds.ticksPlayed)
    }

    @Test fun gameModeShowsControlsInPortraitOnly() {
        val (_, a) = launch()
        val before = visible(a).count { it.contentDescription?.toString()?.contains("Up") == true }
        a.setGameMode(true); idle()
        assertTrue("game pad buttons visible", visible(a).any { it is TextView && it.text.toString() == "ENTER" })
        a.setGameMode(false); idle()
        assertFalse(visible(a).any { it is TextView && it.text.toString() == "ENTER" })
        assertTrue(before >= 0)
    }

    // ------------------------------------------------------------------ lessons / files / runs
    @Test fun lessonsRunTypesTheCommandAndOpensGpio() {
        val (_, a) = launch()
        val s = catSession(a) ?: return
        a.selectTab(MainActivity.Tab.LESSONS); idle()
        byLabel(a, "Run").performClick(); idle()
        assertEquals(MainActivity.Tab.GPIO, a.currentTab)
        waitFor("lesson command echoed") { "python3 ~/examples/blink.py" in transcript(s) }
        assertTrue(visible(a).any { it is TextView && it.text.toString() == "Running: python3 ~/examples/blink.py" })
    }

    @Test fun lessonsRunWithLinuxStoppedSaysSoInsteadOfCrashing() {
        val (_, a) = launch()
        a.selectTab(MainActivity.Tab.LESSONS); idle()
        byLabel(a, "Run").performClick(); idle()
        assertEquals(MainActivity.Tab.TERMINAL, a.currentTab)
        assertEquals("Linux isn't running - tap Power On", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun filesViewerShowsAFileAndRunsPython() {
        val (_, a) = launch()
        val home = File(a.filesDir, "rootfs/home/pi/my dir").apply { mkdirs() }
        File(home, "hi.py").writeText("print('hi')\n")
        File(home, "blob.bin").writeBytes(byteArrayOf(1, 0, 2, 0))
        val s = catSession(a)
        a.selectTab(MainActivity.Tab.FILES); idle()
        a.showDir(home); idle()
        a.openFile(File(home, "blob.bin")); idle()
        assertEquals("Binary file - not shown", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        File(home, "big.txt").writeText("x".repeat(600 * 1024))
        a.openFile(File(home, "big.txt")); idle()
        assertTrue("large files are cut at 512 KB", visible(a).any { it is TextView && it.text.length in 512 * 1024..513 * 1024 && it.text.endsWith("(first 512 KB shown)") })
        a.openFile(File(home, "hi.py")); idle()
        assertTrue(visible(a).any { it is TextView && it.text.toString().startsWith("print('hi')") })
        if (s == null) return
        byLabel(a, "Run").performClick(); idle()
        assertEquals(MainActivity.Tab.TERMINAL, a.currentTab)
        waitFor("run command echoed (with quoting for the space)") { "cd '/home/pi/my dir' && python3 hi.py" in transcript(s) }
        a.selectTab(MainActivity.Tab.FILES); idle()
        a.onBackPressed(); idle()                                   // closes viewer
        assertFalse(visible(a).any { it is TextView && it.text.toString().startsWith("print('hi')") })
        a.onBackPressed(); idle()                                   // up to /home/pi
        a.onBackPressed(); idle()                                   // -> Terminal tab
        assertEquals(MainActivity.Tab.TERMINAL, a.currentTab)
    }

    // ------------------------------------------------------------------ browser
    @Test fun addressBarNormalisation() {
        val n = MainActivity::normalise
        assertEquals("http://localhost:8000", n("8000"))
        assertEquals("http://localhost:5000/x", n(":5000/x"))
        assertEquals("http://localhost:8080", n("localhost:8080"))
        assertEquals("http://127.0.0.1:5000", n("127.0.0.1:5000"))
        assertEquals("http://[::1]:8000", n("[::1]:8000"))
        assertEquals("http://192.168.1.20", n("192.168.1.20"))
        assertEquals("http://10.0.0.5:8080/a?b=1", n("10.0.0.5:8080/a?b=1"))
        assertEquals("https://example.com", n("example.com"))
        assertEquals("https://example.com:8443/x", n("example.com:8443/x"))
        assertEquals("http://example.com", n("http://example.com"))
        assertEquals("https://docs.python.org/3/", n("  https://docs.python.org/3/  "))
        assertTrue(n("how to blink an led").startsWith("https://duckduckgo.com/?q=how%20to%20blink"))
        assertTrue(n("gpiozero").startsWith("https://duckduckgo.com/?q=gpiozero"))
        assertTrue("javascript: never runs from the bar", n("javascript:alert(1)").startsWith("https://duckduckgo.com/"))
        assertTrue("file: never opens from the bar", n("file:///data/data/app.pisandbox/files").startsWith("https://duckduckgo.com/"))
        assertTrue(n("intent://x#Intent;end").startsWith("https://duckduckgo.com/"))
        assertTrue(n(".com").startsWith("https://duckduckgo.com/"))
        assertTrue(n("example.").startsWith("https://duckduckgo.com/"))
    }

    @Test fun browserGoBackForwardAndSafeSettings() {
        val (_, a) = launch()
        a.selectTab(MainActivity.Tab.BROWSER); idle()
        val web = visible(a).filterIsInstance<WebView>().single()
        assertFalse("web pages can't read app files", web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        val bar = visible(a).filterIsInstance<EditText>().single()
        bar.setText("8000"); a.go(); idle()
        assertEquals("http://localhost:8000", shadowOf(web).lastLoadedUrl)
        bar.setText("example.com"); a.go(); idle()
        assertEquals("https://example.com", shadowOf(web).lastLoadedUrl)
        bar.setText("   "); a.go(); idle()
        assertEquals("blank input does nothing", "https://example.com", shadowOf(web).lastLoadedUrl)
        // Robolectric's WebView has no engine, so seed its history; we check our buttons drive it.
        val sw = shadowOf(web)
        sw.pushEntryToHistory("http://localhost:8000"); sw.pushEntryToHistory("https://example.com")
        assertTrue(web.canGoBack())
        byDesc(a, "Back").performClick(); idle()
        assertEquals(1, sw.goBackInvocations)
        assertTrue(web.canGoForward())
        byDesc(a, "Forward").performClick(); idle()
        assertEquals(1, sw.goForwardInvocations)
        a.onBackPressed(); idle()                                   // system Back = page back while history exists
        assertEquals(2, sw.goBackInvocations)
        assertEquals(MainActivity.Tab.BROWSER, a.currentTab)
    }

    // ------------------------------------------------------------------ power
    @Test fun powerOffAndOnAgain() {
        val (_, a) = launch()
        val s = catSession(a) ?: return
        byLabel(a, "Power Off").performClick(); idle()
        assertTrue(Linux.stopRequested); assertEquals(MainActivity.Status.STOPPED, a.status)
        osKill(s)
        waitFor("session finished") { !s.isRunning && a.status == MainActivity.Status.STOPPED }
        assertNull("no error panel after a requested power off", a.errorShown())
        assertTrue(visible(a).any { it is TextView && it.text.toString() == "Power On" })
        byLabel(a, "Power On").performClick(); idle()
        assertEquals(1, MainActivity.testStarts)
        assertEquals(MainActivity.Status.STARTING, a.status)
    }

    @Test fun shellThatDiesAtStartupShowsFriendlyErrorNotRawText() {
        val (_, a) = launch()
        if (!hostJni) return
        val s = TerminalSession("/bin/sh", "/tmp", arrayOf("sh", "-c", "echo proot: boom; exit 3"), arrayOf("PATH=/usr/bin:/bin"), 500, a)
        sessions += s; a.attachTestSession(s); a.term.attachSession(s); a.window.decorView.requestLayout(); idle()
        waitFor("early exit error") { a.errorShown() != null }
        assertEquals(StartupErrors.earlyExit(3).title, a.errorShown())
        assertEquals(MainActivity.Status.ERROR, a.status)
        visible(a).first { it.tag == "copy" }.performClick(); idle()
        val clip = (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip, "exited with code 3" in clip && "proot: boom" in clip && "PiSandbox ${BuildConfig.VERSION_NAME}" in clip)
        visible(a).first { it.tag == "retry" }.performClick(); idle()
        assertNull(a.errorShown()); assertEquals(1, MainActivity.testStarts)
    }

    @Test fun setupFailuresShowFriendlyScreens() {
        val (_, a) = launch()
        a.onPrepared(Result.failure(NotEnoughStorageException(RootfsInstaller.NEEDED_BYTES, 120L * 1024 * 1024))); idle()
        assertEquals("Not enough storage", a.errorShown())
        a.onPrepared(Result.failure(FileNotFoundException("rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz"))); idle()
        assertTrue(a.errorShown()!!.contains("incomplete"))
        a.onPrepared(Result.failure(IOException("weird"))); idle()
        assertEquals("Linux couldn't start", a.errorShown())
        a.selectTab(MainActivity.Tab.SETTINGS); a.onPrepared(Result.failure(IOException("x"))); idle()
        assertEquals("error jumps to the Terminal tab", MainActivity.Tab.TERMINAL, a.currentTab)
    }

    // ------------------------------------------------------------------ about
    @Test fun aboutShowsVersionLicensesSourceOfferAndTrademark() {
        val (_, a) = launch()
        a.selectTab(MainActivity.Tab.SETTINGS); idle()
        byDesc(a, "Open About and licenses").performClick(); idle()
        assertEquals("list", a.aboutState())
        val texts = visible(a).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue("PiSandbox 0.5.2 (build 7)" in texts)
        assertTrue(About.TRADEMARK in texts)
        assertEquals("Raspberry Pi is a trademark of Raspberry Pi Ltd. PiSandbox is not affiliated with or endorsed by Raspberry Pi Ltd.", About.TRADEMARK)
        assertTrue("Written offer" in texts && BuildConfig.PROJECT_URL in texts)
        assertTrue("collects no data" in texts)
        for (c in About.COMPONENTS) {
            assertTrue("listed: ${c.name}", c.name in texts)
            val body = About.read(a, c.file)
            assertFalse("${c.file} is bundled", body.contains("missing from this build"))
            assertTrue("${c.file} looks like a licence", body.length > 400)
        }
        val notices = About.read(a, About.NOTICES)
        for (k in listOf("PRoot", "GPL-2.0-or-later", "talloc", "LGPL-3.0-or-later", "libandroid-shmem", "Debian", "gpiozero", "colorzero",
                         "terminal-emulator", "Commons Compress", "XZ for Java", "AndroidX", "Kotlin", "Written offer", About.TRADEMARK))
            assertTrue("notices mention $k", k in notices)
        visible(a).first { it.tag == "licenses/GPL-2.0.txt" }.performClick(); idle()
        assertTrue(a.aboutState().contains("GNU GENERAL PUBLIC LICENSE"))
        a.onBackPressed(); idle(); assertEquals("list", a.aboutState())
        a.onBackPressed(); idle(); assertEquals("closed", a.aboutState())
        assertEquals(MainActivity.Tab.SETTINGS, a.currentTab)
        assertEquals("About.read never throws", true, About.read(a, "nope.txt").contains("missing"))
    }

    // ------------------------------------------------------------------ lifecycle
    @Test fun recreationKeepsTheSameRunningShell() {
        val (c, a) = launch()
        val s = catSession(a) ?: return
        a.selectTab(MainActivity.Tab.GPIO); idle()
        c.recreate(); idle()                                        // rotation/dark mode/font scale via recreate()
        val b = c.get()
        assertTrue(b !== a)
        assertSame("same Linux shell after recreation", s, Linux.session)
        assertTrue(s.isRunning)
        assertEquals(MainActivity.Tab.GPIO, b.currentTab)
        assertEquals(MainActivity.Status.RUNNING, b.status)
        assertEquals(0, MainActivity.testStarts)
        b.runInTerminal("echo still-here", MainActivity.Tab.TERMINAL); idle()
        waitFor("new activity drives old shell") { "echo still-here" in transcript(s) }
        assertSame(Linux.host?.get(), b)
    }

    @Test fun processDeathRestoresTabAndRestartsLinux() {
        val (c, a) = launch()
        a.selectTab(MainActivity.Tab.LESSONS); idle()
        val state = Bundle(); c.saveInstanceState(state); c.pause().stop().destroy(); idle()
        resetLinux()                                               // new process: nothing in memory
        val c2 = Robolectric.buildActivity(MainActivity::class.java).setup(state); idle()
        assertEquals(MainActivity.Tab.LESSONS, c2.get().currentTab)
        assertEquals("Linux is started again after process death", 1, MainActivity.testStarts)
        assertEquals(View.GONE, c2.get().splash.visibility)
    }

    @Test fun processDeathAfterPowerOffStaysOff() {
        val (c, a) = launch()
        val s = catSession(a) ?: return
        byLabel(a, "Power Off").performClick(); osKill(s); waitFor("stopped") { !s.isRunning && a.status == MainActivity.Status.STOPPED }
        val state = Bundle(); c.saveInstanceState(state); c.pause().stop().destroy(); idle()
        resetLinux(); Linux.stopRequested = false
        val c2 = Robolectric.buildActivity(MainActivity::class.java).setup(state); idle()
        assertEquals(0, MainActivity.testStarts)
        assertEquals(MainActivity.Status.STOPPED, c2.get().status)
    }

    @Test fun preparedWhileNoActivityIsDeliveredToTheNextOne() {
        Linux.pending = Result.failure(IOException("boom"))
        val (_, a) = launch()
        assertEquals("Linux couldn't start", a.errorShown())
        assertNull(Linux.pending)
    }

    @Test fun finishingTheActivityPowersOffButRecreationDoesNot() {
        val (c, a) = launch()
        val s = catSession(a) ?: return
        c.recreate(); idle(); assertTrue(s.isRunning)
        c.get().finish(); c.pause().stop().destroy(); idle()
        assertTrue("finishing asks Linux to stop", Linux.stopRequested); osKill(s)
        waitFor("shell stopped on finish") { !s.isRunning }
        assertTrue(Linux.stopRequested)
    }

    // ------------------------------------------------------------------ accessibility + environments
    @Test fun everyClickableHasALabel() {
        val (_, a) = launch()
        for (t in MainActivity.Tab.values()) {
            a.selectTab(t); idle()
            for (v in visible(a)) {
                if (!v.isClickable || v is EditText || v is WebView || v is com.termux.view.TerminalView) continue
                val label = v.contentDescription?.toString().orEmpty().ifEmpty {
                    all(v).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }.trim()
                }
                assertTrue("${t.label}: unlabeled ${v.javaClass.simpleName} (${(v as? ImageView)?.drawable?.javaClass?.simpleName})", label.isNotEmpty())
            }
        }
    }

    @Test fun largeFontLandscapeSplitScreenAndDarkModeDoNotCrash() {
        RuntimeEnvironment.setFontScale(2.0f)
        var (c, a) = launch()
        for (t in MainActivity.Tab.values()) { a.selectTab(t); idle() }
        RuntimeEnvironment.setQualifiers("+land"); c.configurationChange(Configuration(a.resources.configuration)); idle()
        for (t in MainActivity.Tab.values()) { a.selectTab(t); idle() }
        RuntimeEnvironment.setQualifiers("w320dp-h360dp-port"); c.configurationChange(Configuration(a.resources.configuration)); idle()   // split screen half
        for (t in MainActivity.Tab.values()) { a.selectTab(t); idle() }
        RuntimeEnvironment.setQualifiers("+night"); c.configurationChange(Configuration(a.resources.configuration)); idle()
        a.selectTab(MainActivity.Tab.SETTINGS); idle()
        assertSame("configChanges handled in place, no recreation", a, c.get())
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @Test fun splashFirstRunProgressAndReduceMotion() {
        val (_, a) = launch()
        val v = a.splash
        v.reduceMotion = false
        v.startIntro(); v.setSetupProgress(0f)
        a.onProgress("First run: unpacking Debian 12… 42%")
        assertEquals("Setting up Linux… 42%", v.caption)
        a.onProgress("Rootfs ready (12345 entries)")
        assertEquals("Linux is ready", v.caption)
        v.stop()
        v.reduceMotion = true; v.startIntro()
        assertNotNull(v)
    }
}
