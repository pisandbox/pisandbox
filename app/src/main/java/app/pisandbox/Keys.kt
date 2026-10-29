package app.pisandbox

/** Pure key-mapping helpers (unit-tested). */
object Keys {
    /** Arrow escape: "application cursor keys" mode (set by less/vim/htop via DECCKM) uses ESC O x, bash uses ESC [ x. */
    fun arrow(dir: Char, appCursorMode: Boolean): String = if (appCursorMode) "\u001bO$dir" else "\u001b[$dir"

    /** Same mapping as a real keyboard's Ctrl: Ctrl-c -> 0x03 etc. */
    fun ctrl(cp: Int): Int = when {
        cp in 'a'.code..'z'.code -> cp - 'a'.code + 1
        cp in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        cp == ' '.code || cp == '2'.code || cp == '@'.code -> 0
        cp == '['.code || cp == '3'.code -> 27
        cp == '\\'.code || cp == '4'.code -> 28
        cp == ']'.code || cp == '5'.code -> 29
        cp == '^'.code || cp == '6'.code -> 30
        cp == '_'.code || cp == '7'.code || cp == '/'.code -> 31
        cp == '8'.code || cp == '?'.code -> 127
        else -> cp
    }

    /** Text sent for a typed code point with the on-screen CTRL / ALT keys armed (ALT = ESC prefix, like xterm's metaSendsEscape). */
    fun modified(cp: Int, ctrl: Boolean, alt: Boolean): String {
        val c = if (ctrl) ctrl(cp) else cp
        return (if (alt) "\u001b" else "") + String(Character.toChars(c))
    }

    const val CTRL_C = "\u0003"
    const val ESC = "\u001b"

    /** Shell-quote for typing a path into bash. */
    fun shQuote(s: String) = if (Regex("[A-Za-z0-9_./~+-]+").matches(s)) s else "'" + s.replace("'", "'\\''") + "'"
}
