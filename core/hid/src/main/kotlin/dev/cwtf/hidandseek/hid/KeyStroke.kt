package dev.cwtf.hidandseek.hid

/**
 * One key press: modifiers held, one usage tapped, then everything released.
 *
 * [kind] carries no HID meaning — it exists so the pacer can apply the extra
 * post-newline and post-dead-key delays from the typing profile without having
 * to re-derive what a stroke was for.
 */
data class KeyStroke(
    val modifiers: Modifiers,
    val usage: Int,
    val kind: Kind = Kind.CHARACTER,
    /**
     * Modifiers that stay asserted after this stroke's key is released.
     *
     * Normally none — a stroke presses, releases, and leaves the host clean.
     * Windows Alt-code escapes need Alt held down across a whole run of numpad
     * digits, so those strokes keep it asserted and only the last one drops it.
     * The key itself is always released either way, so nothing can auto-repeat.
     */
    val holdModifiersAfter: Modifiers = Modifiers.NONE,
) {
    enum class Kind {
        CHARACTER,

        /** Enter/Return. Hosts often do visible work here and drop input during it. */
        NEWLINE,

        /** A dead key awaiting its base character; the host needs time to compose. */
        DEAD_KEY,

        /** Navigation, function, and editing keys — including retraction backspaces. */
        CONTROL,
    }

    /** The key-down report for this stroke. */
    fun downReport(): KeyboardReport = KeyboardReport.of(modifiers, usage)

    /**
     * The report that releases this stroke's key.
     *
     * All-zero in the normal case; modifier-only when a run holds modifiers
     * across several keys.
     */
    fun upReport(): KeyboardReport =
        if (holdModifiersAfter.isEmpty) KeyboardReport.RELEASE_ALL
        else KeyboardReport.of(holdModifiersAfter)

    companion object {
        fun character(usage: Int, modifiers: Modifiers = Modifiers.NONE) =
            KeyStroke(modifiers, usage, Kind.CHARACTER)

        fun control(usage: Int, modifiers: Modifiers = Modifiers.NONE) =
            KeyStroke(modifiers, usage, Kind.CONTROL)

        val ENTER = KeyStroke(Modifiers.NONE, Usage.ENTER, Kind.NEWLINE)
        val BACKSPACE = KeyStroke(Modifiers.NONE, Usage.BACKSPACE, Kind.CONTROL)
        val TAB = KeyStroke(Modifiers.NONE, Usage.TAB, Kind.CHARACTER)
    }
}
