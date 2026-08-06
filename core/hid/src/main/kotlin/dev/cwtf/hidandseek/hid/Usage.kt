package dev.cwtf.hidandseek.hid

/**
 * HID Keyboard/Keypad usage codes (usage page 0x07).
 *
 * These identify a *physical key position*, not a character — the host decodes
 * them through its own keyboard layout. That indirection is why [KeyLayout]
 * exists and why the host's layout has to be configured per device.
 */
object Usage {
    const val A = 0x04
    const val Z = 0x1D

    const val DIGIT_1 = 0x1E
    const val DIGIT_9 = 0x26
    const val DIGIT_0 = 0x27

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUAL = 0x2E
    const val LEFT_BRACKET = 0x2F
    const val RIGHT_BRACKET = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val APOSTROPHE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val CAPS_LOCK = 0x39

    const val F1 = 0x3A
    const val F12 = 0x45

    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val ARROW_RIGHT = 0x4F
    const val ARROW_LEFT = 0x50
    const val ARROW_DOWN = 0x51
    const val ARROW_UP = 0x52

    const val NUM_LOCK = 0x53
    const val KEYPAD_1 = 0x59
    const val KEYPAD_9 = 0x61
    const val KEYPAD_0 = 0x62

    /** Usage for the letter [c], which must be in `a`..`z`. */
    fun letter(c: Char): Int {
        require(c in 'a'..'z') { "Not a lowercase letter: $c" }
        return A + (c - 'a')
    }

    /** Usage for the digit [c], which must be in `0`..`9`. */
    fun digit(c: Char): Int {
        require(c in '0'..'9') { "Not a digit: $c" }
        return if (c == '0') DIGIT_0 else DIGIT_1 + (c - '1')
    }

    /** Numpad usage for the digit [c] — used by the Windows Alt-code escape path. */
    fun keypadDigit(c: Char): Int {
        require(c in '0'..'9') { "Not a digit: $c" }
        return if (c == '0') KEYPAD_0 else KEYPAD_1 + (c - '1')
    }

    fun functionKey(n: Int): Int {
        require(n in 1..12) { "Function keys run F1..F12, got F$n" }
        return F1 + (n - 1)
    }
}
