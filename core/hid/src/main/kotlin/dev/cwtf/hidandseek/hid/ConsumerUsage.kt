package dev.cwtf.hidandseek.hid

/**
 * Consumer Control usage codes (usage page 0x0C), sent on report ID 2.
 *
 * Media and system keys a keyboard collection cannot express — the host routes
 * these to whatever is playing rather than to the focused text field.
 */
object ConsumerUsage {
    const val PLAY_PAUSE = 0xCD
    const val SCAN_NEXT = 0xB5
    const val SCAN_PREVIOUS = 0xB6
    const val STOP = 0xB7
    const val MUTE = 0xE2
    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val BRIGHTNESS_UP = 0x6F
    const val BRIGHTNESS_DOWN = 0x70

    /** Offered in the macro sheet, in display order. */
    val MEDIA_KEYS: List<Pair<String, Int>> = listOf(
        "Prev" to SCAN_PREVIOUS,
        "Play/Pause" to PLAY_PAUSE,
        "Next" to SCAN_NEXT,
        "Vol −" to VOLUME_DOWN,
        "Mute" to MUTE,
        "Vol +" to VOLUME_UP,
    )
}

/**
 * Keys the staging text area cannot express, grouped for the macro sheet.
 *
 * These are the keys that matter when driving a machine rather than writing
 * into one: escaping a menu, tabbing between fields, navigating a BIOS.
 */
object SpecialKeys {

    val EDITING: List<Pair<String, Int>> = listOf(
        "Esc" to Usage.ESCAPE,
        "Tab" to Usage.TAB,
        "Enter" to Usage.ENTER,
        "Bksp" to Usage.BACKSPACE,
        "Del" to Usage.DELETE,
        "Ins" to Usage.INSERT,
    )

    val NAVIGATION: List<Pair<String, Int>> = listOf(
        "Home" to Usage.HOME,
        "End" to Usage.END,
        "PgUp" to Usage.PAGE_UP,
        "PgDn" to Usage.PAGE_DOWN,
    )

    val ARROWS: List<Pair<String, Int>> = listOf(
        "←" to Usage.ARROW_LEFT,
        "↑" to Usage.ARROW_UP,
        "↓" to Usage.ARROW_DOWN,
        "→" to Usage.ARROW_RIGHT,
    )

    val FUNCTION: List<Pair<String, Int>> = (1..12).map { "F$it" to Usage.functionKey(it) }

    val SYSTEM: List<Pair<String, Int>> = listOf(
        "PrtSc" to Usage.PRINT_SCREEN,
        "Pause" to Usage.PAUSE,
        "ScrLk" to Usage.SCROLL_LOCK,
        "NumLk" to Usage.NUM_LOCK,
        "CapsLk" to Usage.CAPS_LOCK,
    )
}
