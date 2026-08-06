package dev.cwtf.hidandseek.hid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyComboTest {

    @Test
    fun `a bare named key parses`() {
        val stroke = assertNotNull(KeyCombo.parse("enter"))
        assertEquals(Usage.ENTER, stroke.usage)
        assertEquals(Modifiers.NONE, stroke.modifiers)
    }

    @Test
    fun `modifiers combine`() {
        val stroke = assertNotNull(KeyCombo.parse("ctrl+alt+delete"))
        assertEquals(Usage.DELETE, stroke.usage)
        assertEquals(Modifiers.LEFT_CTRL + Modifiers.LEFT_ALT, stroke.modifiers)
    }

    @Test
    fun `a printable character resolves through the layout`() {
        val stroke = assertNotNull(KeyCombo.parse("ctrl+c"))
        assertEquals(Usage.letter('c'), stroke.usage)
        assertEquals(Modifiers.LEFT_CTRL, stroke.modifiers)
    }

    @Test
    fun `the layout's own modifiers combine with the combo's`() {
        // '?' is shift+slash on US, so ctrl+? must carry both.
        val stroke = assertNotNull(KeyCombo.parse("ctrl+?"))
        assertEquals(Usage.SLASH, stroke.usage)
        assertEquals(Modifiers.LEFT_CTRL + Modifiers.LEFT_SHIFT, stroke.modifiers)
    }

    @Test
    fun `platform modifier aliases map to GUI`() {
        listOf("cmd+space", "win+space", "super+space", "meta+space").forEach { combo ->
            assertEquals(
                Modifiers.LEFT_GUI,
                assertNotNull(KeyCombo.parse(combo)).modifiers,
                "failed for $combo",
            )
        }
    }

    @Test
    fun `function keys parse`() {
        assertEquals(Usage.functionKey(2), assertNotNull(KeyCombo.parse("ctrl+alt+f2")).usage)
    }

    @Test
    fun `case and dash separators are accepted`() {
        assertEquals(KeyCombo.parse("ctrl+alt+t"), KeyCombo.parse("Ctrl-Alt-T"))
    }

    @Test
    fun `an unknown modifier is rejected rather than guessed`() {
        assertNull(KeyCombo.parse("hyper+t"))
    }

    @Test
    fun `an unknown key name is rejected`() {
        assertNull(KeyCombo.parse("ctrl+launchpad"))
    }

    @Test
    fun `empty input is rejected`() {
        assertNull(KeyCombo.parse(""))
        assertNull(KeyCombo.parse("   "))
    }

    @Test
    fun `a character absent from the layout is rejected`() {
        assertNull(KeyCombo.parse("ctrl+é"), "US layout cannot produce this")
    }

    @Test
    fun `every preset combo parses`() {
        KeyCombo.PRESETS.forEach { (label, combo) ->
            assertNotNull(KeyCombo.parse(combo), "preset \"$label\" ($combo) failed to parse")
        }
    }
}
