package dev.cwtf.hidandseek.hid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayoutMapperTest {

    private val us = BuiltInLayouts.US_QWERTY

    /** Text in, reports out, reports decoded back to text — must be identical. */
    private fun roundTrip(text: String, layout: KeyLayout = us): String {
        val strokes = LayoutMapper(layout).map(text).strokes
        val transport = FakeHidTransport(TransportState.CONNECTED)
        for (stroke in strokes) {
            transport.recordDirect(stroke)
        }
        return transport.decodeTypedText(layout)
    }

    @Test
    fun `every printable ASCII character round-trips on US layout`() {
        val printable = (0x20..0x7E).map { it.toChar() }.joinToString("")
        assertEquals(printable, roundTrip(printable))
    }

    @Test
    fun `every printable ASCII character has a binding`() {
        val missing = (0x20..0x7E)
            .map { it.toChar() }
            .filter { us.sequenceFor(it.code) == null }
        assertTrue(missing.isEmpty(), "US layout is missing bindings for: $missing")
    }

    @Test
    fun `letters use shift only for uppercase`() {
        val lower = LayoutMapper(us).map("a").strokes.single()
        val upper = LayoutMapper(us).map("A").strokes.single()

        assertEquals(Usage.letter('a'), lower.usage)
        assertEquals(Modifiers.NONE, lower.modifiers)
        assertEquals(Usage.letter('a'), upper.usage, "same physical key")
        assertEquals(Modifiers.LEFT_SHIFT, upper.modifiers)
    }

    @Test
    fun `shifted symbols share the unshifted key`() {
        val nine = LayoutMapper(us).map("9").strokes.single()
        val paren = LayoutMapper(us).map("(").strokes.single()
        assertEquals(nine.usage, paren.usage)
        assertEquals(Modifiers.LEFT_SHIFT, paren.modifiers)
    }

    @Test
    fun `newline maps to Enter and is marked for the extra delay`() {
        val strokes = LayoutMapper(us).map("a\nb").strokes
        assertEquals(3, strokes.size)
        assertEquals(Usage.ENTER, strokes[1].usage)
        assertEquals(KeyStroke.Kind.NEWLINE, strokes[1].kind)
    }

    @Test
    fun `CRLF collapses to a single Enter`() {
        val strokes = LayoutMapper(us).map("a\r\nb").strokes
        assertEquals(3, strokes.size, "CRLF must not produce two Enters")
        assertEquals(Usage.ENTER, strokes[1].usage)
    }

    @Test
    fun `bare CR is treated as a newline`() {
        val strokes = LayoutMapper(us).map("a\rb").strokes
        assertEquals(3, strokes.size)
        assertEquals(Usage.ENTER, strokes[1].usage)
    }

    @Test
    fun `host caps lock inverts letter shifting but leaves digits alone`() {
        val mapper = LayoutMapper(us, hostCapsLock = true)

        val lower = mapper.map("a").strokes.single()
        val upper = mapper.map("A").strokes.single()
        assertEquals(
            Modifiers.LEFT_SHIFT,
            lower.modifiers,
            "with host Caps Lock on, lowercase needs shift",
        )
        assertEquals(Modifiers.NONE, upper.modifiers)

        val dollar = mapper.map("$").strokes.single()
        assertEquals(
            Modifiers.LEFT_SHIFT,
            dollar.modifiers,
            "Caps Lock must not affect symbols, or \$ would become 4",
        )
    }

    @Test
    fun `unmappable characters are skipped and reported by default`() {
        val result = LayoutMapper(us).map("hi 🎉 there")
        assertEquals(1, result.unmappable.size)
        assertEquals("🎉", result.unmappable.single().text)
        assertEquals(UnmappableHandling.SKIPPED, result.unmappable.single().handling)
        assertTrue(result.hasLoss)

        val transport = FakeHidTransport(TransportState.CONNECTED)
        result.strokes.forEach { transport.recordDirect(it) }
        assertEquals("hi  there", transport.decodeTypedText(us))
    }

    @Test
    fun `substitute policy replaces unmappable characters`() {
        val result = LayoutMapper(us, UnmappablePolicy.Substitute('?')).map("a🎉b")
        assertEquals(UnmappableHandling.SUBSTITUTED, result.unmappable.single().handling)

        val transport = FakeHidTransport(TransportState.CONNECTED)
        result.strokes.forEach { transport.recordDirect(it) }
        assertEquals("a?b", transport.decodeTypedText(us))
    }

    @Test
    fun `linux unicode escape emits ctrl-shift-u then hex then enter`() {
        val policy = UnmappablePolicy.UnicodeEscape(HostOs.LINUX)
        val result = LayoutMapper(us, policy).map("é")

        assertEquals(UnmappableHandling.ESCAPED, result.unmappable.single().handling)
        val strokes = result.strokes
        assertEquals(Usage.letter('u'), strokes.first().usage)
        assertEquals(Modifiers.LEFT_CTRL + Modifiers.LEFT_SHIFT, strokes.first().modifiers)
        assertEquals(Usage.ENTER, strokes.last().usage)
        // e9 -> 'e', '9'
        assertEquals(Usage.letter('e'), strokes[1].usage)
        assertEquals(Usage.digit('9'), strokes[2].usage)
    }

    @Test
    fun `windows alt code holds alt across the digit run and releases at the end`() {
        val policy = UnmappablePolicy.UnicodeEscape(HostOs.WINDOWS)
        val strokes = LayoutMapper(us, policy).map("é").strokes

        assertEquals(3, strokes.size, "233 decimal is three numpad digits")
        assertTrue(strokes.all { Modifiers.LEFT_ALT in it.modifiers })
        assertEquals(Usage.keypadDigit('2'), strokes[0].usage)
        assertEquals(
            Modifiers.LEFT_ALT,
            strokes[0].holdModifiersAfter,
            "Alt must stay down between digits or the host sees separate presses",
        )
        assertEquals(
            Modifiers.NONE,
            strokes.last().holdModifiersAfter,
            "the final digit releases Alt, which is what commits the character",
        )
    }

    @Test
    fun `macos unicode escape falls back to skipping and says so`() {
        val policy = UnmappablePolicy.UnicodeEscape(HostOs.MACOS)
        val result = LayoutMapper(us, policy).map("é")
        assertTrue(result.strokes.isEmpty())
        assertEquals(UnmappableHandling.SKIPPED, result.unmappable.single().handling)
    }

    @Test
    fun `US international reaches accented characters via AltGr`() {
        val intl = BuiltInLayouts.US_INTERNATIONAL
        val stroke = LayoutMapper(intl).map("é").strokes.single()
        assertEquals(Usage.letter('e'), stroke.usage)
        assertEquals(Modifiers.RIGHT_ALT, stroke.modifiers)

        assertEquals("café", roundTrip("café", intl))
    }

    @Test
    fun `layouts parse from JSON including dead key sequences`() {
        val layout = KeyLayout.fromJson(
            """
            {
              "id": "test", "name": "Test",
              "map": {
                "z": { "usage": 29 },
                "@": { "usage": 31, "mods": ["RALT"] },
                "é": [ { "usage": 46, "dead": true }, { "usage": 8 } ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("test", layout.id)
        assertEquals(29, layout.sequenceFor('z'.code)!!.single().usage)
        assertEquals(Modifiers.RIGHT_ALT, layout.sequenceFor('@'.code)!!.single().modifiers)

        val composed = assertNotNull(layout.sequenceFor('é'.code))
        assertEquals(2, composed.size)
        assertTrue(composed[0].dead, "first binding of a composed character is the dead key")

        val strokes = LayoutMapper(layout).map("é").strokes
        assertEquals(KeyStroke.Kind.DEAD_KEY, strokes[0].kind)
        assertEquals(KeyStroke.Kind.CHARACTER, strokes[1].kind)
    }
}

/** Records a stroke's reports without going through the pacer's timing. */
private fun FakeHidTransport.recordDirect(stroke: KeyStroke) {
    kotlinx.coroutines.runBlocking {
        sendKeyboardReport(stroke.downReport())
        sendKeyboardReport(stroke.upReport())
    }
}
