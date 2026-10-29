package app.pisandbox

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {
    private class Sink : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    @Test fun arrowsFollowDeccKm() {
        val emu = TerminalEmulator(Sink(), 80, 24, 10, 20, 100, null)
        assertFalse(emu.isCursorKeysApplicationMode)
        assertEquals("\u001b[A", Keys.arrow('A', emu.isCursorKeysApplicationMode))   // bash prompt
        val on = "\u001b[?1h".toByteArray(); emu.append(on, on.size)                 // less / vim / htop enable DECCKM
        assertTrue(emu.isCursorKeysApplicationMode)
        assertEquals("\u001bOA", Keys.arrow('A', emu.isCursorKeysApplicationMode))
        // identical to what termux sends for a hardware arrow key
        for ((dir, code) in listOf('A' to KeyEvent.KEYCODE_DPAD_UP, 'B' to KeyEvent.KEYCODE_DPAD_DOWN, 'C' to KeyEvent.KEYCODE_DPAD_RIGHT, 'D' to KeyEvent.KEYCODE_DPAD_LEFT))
            for (app in listOf(false, true)) assertEquals(KeyHandler.getCode(code, 0, app, false), Keys.arrow(dir, app))
        val off = "\u001b[?1l".toByteArray(); emu.append(off, off.size)
        assertEquals("\u001b[B", Keys.arrow('B', emu.isCursorKeysApplicationMode))
    }

    @Test fun ctrlAndAlt() {
        assertEquals("\u0003", Keys.modified('c'.code, ctrl = true, alt = false))   // CTRL then c = interrupt
        assertEquals("\u0003", Keys.modified('C'.code, ctrl = true, alt = false))
        assertEquals("\u0004", Keys.modified('d'.code, ctrl = true, alt = false))
        assertEquals("\u001a", Keys.modified('z'.code, ctrl = true, alt = false))
        assertEquals("\u001b", Keys.modified('['.code, ctrl = true, alt = false))
        assertEquals("\u001bb", Keys.modified('b'.code, ctrl = false, alt = true))   // ALT-b = word back in bash
        assertEquals("x", Keys.modified('x'.code, ctrl = false, alt = false))
        assertEquals("\u0003", Keys.CTRL_C)
    }

    @Test fun quoting() {
        assertEquals("/home/pi/examples", Keys.shQuote("/home/pi/examples"))
        assertEquals("'my file.py'", Keys.shQuote("my file.py"))
        assertEquals("'it'\\''s.py'", Keys.shQuote("it's.py"))
    }
}
