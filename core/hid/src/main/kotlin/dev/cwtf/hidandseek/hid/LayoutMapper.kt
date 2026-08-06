package dev.cwtf.hidandseek.hid

enum class HostOs { WINDOWS, MACOS, LINUX, ANDROID, IOS, TV, OTHER }

/** What to do with a character the host's layout cannot produce. */
sealed interface UnmappablePolicy {

    /** Drop it, count it, report it. The default — never silently wrong. */
    data object Skip : UnmappablePolicy

    /** Replace it with an ASCII stand-in. */
    data class Substitute(val replacement: Char = '?') : UnmappablePolicy

    /**
     * Emit the host's Unicode entry sequence.
     *
     * Supported on Linux (IBus `Ctrl+Shift+U`) and Windows (Alt + numpad
     * decimal, which needs a numpad and the `EnableHexNumpad` registry value).
     * macOS has no equivalent, so characters fall back to [Skip] there and are
     * reported as skipped rather than silently dropped.
     */
    data class UnicodeEscape(val hostOs: HostOs) : UnmappablePolicy
}

enum class UnmappableHandling { SKIPPED, SUBSTITUTED, ESCAPED }

data class UnmappableChar(
    /** Index into the source string, in code points. */
    val index: Int,
    val text: String,
    val handling: UnmappableHandling,
)

data class MappingResult(
    val strokes: List<KeyStroke>,
    val unmappable: List<UnmappableChar>,
    /**
     * Parallel to [strokes]: how many source characters are fully delivered
     * once stroke `i` completes.
     *
     * Strokes and characters are not one-to-one — escapes expand to several
     * strokes and skipped characters produce none — so a send that dies partway
     * can only report what the host actually received by consulting this.
     */
    val charBoundaries: List<Int> = emptyList(),
) {
    val skipped: List<UnmappableChar> get() = unmappable.filter { it.handling == UnmappableHandling.SKIPPED }
    val hasLoss: Boolean get() = skipped.isNotEmpty()

    /** Source characters confirmed delivered after [strokesSent] strokes. */
    fun charsDeliveredAfter(strokesSent: Int): Int = when {
        strokesSent <= 0 -> 0
        charBoundaries.isEmpty() -> 0
        else -> charBoundaries[(strokesSent - 1).coerceAtMost(charBoundaries.lastIndex)]
    }
}

/**
 * Turns text into the key presses that reproduce it on a host.
 *
 * @param hostCapsLock when the host has Caps Lock on, letter keys produce the
 *   opposite case, so the shift state the layout specifies has to be inverted
 *   for letters. Sourced from the host's LED output reports where available.
 */
class LayoutMapper(
    val layout: KeyLayout,
    private val policy: UnmappablePolicy = UnmappablePolicy.Skip,
    private val hostCapsLock: Boolean = false,
) {

    fun map(text: String): MappingResult {
        val strokes = mutableListOf<KeyStroke>()
        val unmappable = mutableListOf<UnmappableChar>()
        val boundaries = mutableListOf<Int>()

        var i = 0
        var codePointIndex = 0
        while (i < text.length) {
            val before = strokes.size

            // Collapse CRLF (and a bare CR) into one Enter so hosts don't see a
            // stray blank line.
            if (text[i] == '\r') {
                strokes += KeyStroke.ENTER
                i += if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                codePointIndex++
                recordBoundaries(boundaries, strokes.size - before, codePointIndex)
                continue
            }

            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val asString = String(Character.toChars(codePoint))

            val sequence = layout.sequenceFor(codePoint)
            if (sequence != null) {
                strokes += sequence.map { it.toStroke(codePoint) }
            } else {
                handleUnmappable(codePoint, asString, codePointIndex, strokes, unmappable)
            }

            i += charCount
            codePointIndex++
            recordBoundaries(boundaries, strokes.size - before, codePointIndex)
        }

        return MappingResult(strokes, unmappable, boundaries)
    }

    /**
     * A character only counts as delivered once its *last* stroke lands, so
     * every stroke but the final one of a multi-stroke sequence records the
     * previous total.
     */
    private fun recordBoundaries(
        boundaries: MutableList<Int>,
        added: Int,
        charsCompleted: Int,
    ) {
        repeat(added) { index ->
            boundaries += if (index == added - 1) charsCompleted else charsCompleted - 1
        }
    }

    private fun KeyBinding.toStroke(codePoint: Int): KeyStroke {
        val kind = when {
            dead -> KeyStroke.Kind.DEAD_KEY
            usage == Usage.ENTER -> KeyStroke.Kind.NEWLINE
            else -> KeyStroke.Kind.CHARACTER
        }
        return KeyStroke(effectiveModifiers(codePoint), usage, kind)
    }

    /**
     * Inverts shift for letters when the host's Caps Lock is on.
     *
     * Applies only to letters: Caps Lock does not affect digits or symbols, so
     * inverting those would break `4` into `$`.
     */
    private fun KeyBinding.effectiveModifiers(codePoint: Int): Modifiers {
        if (!hostCapsLock || !Character.isLetter(codePoint)) return modifiers
        return if (Modifiers.LEFT_SHIFT in modifiers) {
            Modifiers(modifiers.bits and Modifiers.LEFT_SHIFT.bits.inv())
        } else {
            modifiers + Modifiers.LEFT_SHIFT
        }
    }

    private fun handleUnmappable(
        codePoint: Int,
        asString: String,
        index: Int,
        strokes: MutableList<KeyStroke>,
        unmappable: MutableList<UnmappableChar>,
    ) {
        when (policy) {
            UnmappablePolicy.Skip -> {
                unmappable += UnmappableChar(index, asString, UnmappableHandling.SKIPPED)
            }

            is UnmappablePolicy.Substitute -> {
                val replacement = layout.sequenceFor(policy.replacement.code)
                if (replacement == null) {
                    // The substitution character isn't in this layout either.
                    unmappable += UnmappableChar(index, asString, UnmappableHandling.SKIPPED)
                } else {
                    strokes += replacement.map { it.toStroke(policy.replacement.code) }
                    unmappable += UnmappableChar(index, asString, UnmappableHandling.SUBSTITUTED)
                }
            }

            is UnmappablePolicy.UnicodeEscape -> {
                val escaped = when (policy.hostOs) {
                    HostOs.LINUX -> linuxEscape(codePoint)
                    HostOs.WINDOWS -> windowsAltCode(codePoint)
                    else -> null
                }
                if (escaped == null) {
                    unmappable += UnmappableChar(index, asString, UnmappableHandling.SKIPPED)
                } else {
                    strokes += escaped
                    unmappable += UnmappableChar(index, asString, UnmappableHandling.ESCAPED)
                }
            }
        }
    }

    /** IBus: `Ctrl+Shift+U`, the code point in hex, then Enter. */
    private fun linuxEscape(codePoint: Int): List<KeyStroke>? {
        val hex = codePoint.toString(16)
        val digits = hex.map { layout.sequenceFor(it.code)?.singleOrNull() ?: return null }
        return buildList {
            add(
                KeyStroke(
                    Modifiers.LEFT_CTRL + Modifiers.LEFT_SHIFT,
                    Usage.letter('u'),
                    KeyStroke.Kind.CONTROL,
                ),
            )
            digits.forEach { add(KeyStroke(it.modifiers, it.usage, KeyStroke.Kind.CHARACTER)) }
            add(KeyStroke.ENTER)
        }
    }

    /**
     * Windows: Alt held while the decimal code point is typed on the numpad.
     *
     * Alt must stay down for the whole run, so every digit but the last keeps
     * it asserted via [KeyStroke.holdModifiersAfter]. Only code points that fit
     * the plain Alt+decimal form are attempted.
     */
    private fun windowsAltCode(codePoint: Int): List<KeyStroke>? {
        if (codePoint > 0xFFFF) return null
        val digits = codePoint.toString(10)
        return digits.mapIndexed { i, digit ->
            KeyStroke(
                modifiers = Modifiers.LEFT_ALT,
                usage = Usage.keypadDigit(digit),
                kind = KeyStroke.Kind.CONTROL,
                holdModifiersAfter =
                    if (i == digits.lastIndex) Modifiers.NONE else Modifiers.LEFT_ALT,
            )
        }
    }
}
