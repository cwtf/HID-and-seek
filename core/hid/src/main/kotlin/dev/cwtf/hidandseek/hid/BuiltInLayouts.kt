package dev.cwtf.hidandseek.hid

/** The layouts compiled into the app. Others load from JSON assets. */
object BuiltInLayouts {

    val US_QWERTY: KeyLayout = buildKeyLayout("us", "US QWERTY") {
        usCommon()
    }

    /**
     * US-International: identical key positions to [US_QWERTY], plus AltGr
     * combinations for common accented characters and symbols.
     *
     * Only the AltGr combinations are added — the dead-key behaviour of the
     * real US-International layout (where `'` then `e` gives `é`) is
     * deliberately not modelled, because typing a bare apostrophe would then
     * require a trailing space and every send containing `'` would be wrong.
     * AltGr combinations produce the same characters unambiguously.
     */
    val US_INTERNATIONAL: KeyLayout = buildKeyLayout("us_intl", "US International") {
        usCommon()

        val altGr = Modifiers.RIGHT_ALT
        val altGrShift = Modifiers.RIGHT_ALT + Modifiers.LEFT_SHIFT

        key('á', Usage.letter('a'), altGr)
        key('é', Usage.letter('e'), altGr)
        key('í', Usage.letter('i'), altGr)
        key('ó', Usage.letter('o'), altGr)
        key('ú', Usage.letter('u'), altGr)
        key('ý', Usage.letter('y'), altGr)
        key('Á', Usage.letter('a'), altGrShift)
        key('É', Usage.letter('e'), altGrShift)
        key('Í', Usage.letter('i'), altGrShift)
        key('Ó', Usage.letter('o'), altGrShift)
        key('Ú', Usage.letter('u'), altGrShift)

        key('ä', Usage.letter('q'), altGr)
        key('ö', Usage.letter('p'), altGr)
        key('ü', Usage.letter('y'), altGrShift)
        key('ñ', Usage.letter('n'), altGr)
        key('ç', Usage.letter('c'), altGr)
        key('å', Usage.letter('w'), altGr)
        key('ø', Usage.letter('l'), altGr)
        key('æ', Usage.letter('z'), altGr)
        key('ß', Usage.letter('s'), altGr)

        key('€', Usage.digit('5'), altGr)
        key('£', Usage.digit('3'), altGr)
        key('¥', Usage.digit('6'), altGr)
        key('¢', Usage.letter('c'), altGrShift)
        key('°', Usage.SEMICOLON, altGr)
        key('±', Usage.EQUAL, altGrShift)
        key('«', Usage.LEFT_BRACKET, altGr)
        key('»', Usage.RIGHT_BRACKET, altGr)
        key('¿', Usage.SLASH, altGr)
        key('¡', Usage.DIGIT_1, altGr)
    }

    val ALL: List<KeyLayout> = listOf(US_QWERTY, US_INTERNATIONAL)

    val DEFAULT: KeyLayout = US_QWERTY

    fun byId(id: String): KeyLayout? = ALL.firstOrNull { it.id == id }
}

/** The key positions shared by every US-derived layout. */
private fun KeyLayoutBuilder.usCommon() {
    for (c in 'a'..'z') {
        key(c, Usage.letter(c))
        key(c.uppercaseChar(), Usage.letter(c), Modifiers.LEFT_SHIFT)
    }

    key('1', Usage.digit('1')); key('!', Usage.digit('1'), Modifiers.LEFT_SHIFT)
    key('2', Usage.digit('2')); key('@', Usage.digit('2'), Modifiers.LEFT_SHIFT)
    key('3', Usage.digit('3')); key('#', Usage.digit('3'), Modifiers.LEFT_SHIFT)
    key('4', Usage.digit('4')); key('$', Usage.digit('4'), Modifiers.LEFT_SHIFT)
    key('5', Usage.digit('5')); key('%', Usage.digit('5'), Modifiers.LEFT_SHIFT)
    key('6', Usage.digit('6')); key('^', Usage.digit('6'), Modifiers.LEFT_SHIFT)
    key('7', Usage.digit('7')); key('&', Usage.digit('7'), Modifiers.LEFT_SHIFT)
    key('8', Usage.digit('8')); key('*', Usage.digit('8'), Modifiers.LEFT_SHIFT)
    key('9', Usage.digit('9')); key('(', Usage.digit('9'), Modifiers.LEFT_SHIFT)
    key('0', Usage.digit('0')); key(')', Usage.digit('0'), Modifiers.LEFT_SHIFT)

    pair('-', '_', Usage.MINUS)
    pair('=', '+', Usage.EQUAL)
    pair('[', '{', Usage.LEFT_BRACKET)
    pair(']', '}', Usage.RIGHT_BRACKET)
    pair('\\', '|', Usage.BACKSLASH)
    pair(';', ':', Usage.SEMICOLON)
    pair('\'', '"', Usage.APOSTROPHE)
    pair('`', '~', Usage.GRAVE)
    pair(',', '<', Usage.COMMA)
    pair('.', '>', Usage.PERIOD)
    pair('/', '?', Usage.SLASH)

    key(' ', Usage.SPACE)
    key('\t', Usage.TAB)
    key('\n', Usage.ENTER)
}
