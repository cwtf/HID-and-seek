package dev.cwtf.hidandseek.hid

/**
 * Parses key combinations like `ctrl+alt+t` or `cmd+space`.
 *
 * Used by macros and by the agent's `press_keys` tool. Unknown names return
 * null rather than a best guess: pressing the wrong key on someone's machine is
 * worse than pressing nothing and saying so.
 */
object KeyCombo {

    private val NAMED_KEYS: Map<String, Int> = buildMap {
        put("enter", Usage.ENTER)
        put("return", Usage.ENTER)
        put("esc", Usage.ESCAPE)
        put("escape", Usage.ESCAPE)
        put("backspace", Usage.BACKSPACE)
        put("tab", Usage.TAB)
        put("space", Usage.SPACE)
        put("spacebar", Usage.SPACE)
        put("delete", Usage.DELETE)
        put("del", Usage.DELETE)
        put("insert", Usage.INSERT)
        put("home", Usage.HOME)
        put("end", Usage.END)
        put("pageup", Usage.PAGE_UP)
        put("pgup", Usage.PAGE_UP)
        put("pagedown", Usage.PAGE_DOWN)
        put("pgdn", Usage.PAGE_DOWN)
        put("up", Usage.ARROW_UP)
        put("down", Usage.ARROW_DOWN)
        put("left", Usage.ARROW_LEFT)
        put("right", Usage.ARROW_RIGHT)
        put("capslock", Usage.CAPS_LOCK)
        put("printscreen", Usage.PRINT_SCREEN)
        put("prtsc", Usage.PRINT_SCREEN)
        put("pause", Usage.PAUSE)
        put("scrolllock", Usage.SCROLL_LOCK)
        put("numlock", Usage.NUM_LOCK)
        for (n in 1..12) put("f$n", Usage.functionKey(n))
    }

    private fun modifierFor(name: String): Modifiers? = when (name) {
        "ctrl", "control", "lctrl" -> Modifiers.LEFT_CTRL
        "rctrl" -> Modifiers.RIGHT_CTRL
        "shift", "lshift" -> Modifiers.LEFT_SHIFT
        "rshift" -> Modifiers.RIGHT_SHIFT
        "alt", "lalt", "option" -> Modifiers.LEFT_ALT
        "ralt", "altgr" -> Modifiers.RIGHT_ALT
        "cmd", "command", "meta", "super", "win", "gui", "lgui" -> Modifiers.LEFT_GUI
        "rgui" -> Modifiers.RIGHT_GUI
        else -> null
    }

    /**
     * Parses [combo] into a single keystroke.
     *
     * The final segment is the key; everything before it must be a modifier.
     * [layout] resolves single printable characters, so `ctrl+shift+u` works on
     * any layout that can produce `u`.
     */
    fun parse(combo: String, layout: KeyLayout = BuiltInLayouts.DEFAULT): KeyStroke? {
        val parts = combo.trim().lowercase()
            .split('+', '-')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val keyName = parts.last()
        var modifiers = Modifiers.NONE
        for (part in parts.dropLast(1)) {
            modifiers += modifierFor(part) ?: return null
        }

        NAMED_KEYS[keyName]?.let {
            return KeyStroke(modifiers, it, KeyStroke.Kind.CONTROL)
        }

        // A single printable character, resolved through the host's layout.
        if (keyName.codePointCount(0, keyName.length) == 1) {
            val binding = layout.sequenceFor(keyName.codePointAt(0))?.singleOrNull()
                ?: return null
            // The layout's own modifiers (a shifted symbol, say) combine with
            // the ones the combo asked for.
            return KeyStroke(modifiers + binding.modifiers, binding.usage, KeyStroke.Kind.CONTROL)
        }

        return null
    }

    /** Named combos offered in the macro sheet. */
    val PRESETS: Map<String, String> = linkedMapOf(
        "Ctrl+Alt+Del" to "ctrl+alt+delete",
        "Alt+Tab" to "alt+tab",
        "Ctrl+C" to "ctrl+c",
        "Ctrl+V" to "ctrl+v",
        "Ctrl+Z" to "ctrl+z",
        "Win+R" to "gui+r",
        "Cmd+Space" to "cmd+space",
        "Ctrl+Alt+F2" to "ctrl+alt+f2",
    )
}
