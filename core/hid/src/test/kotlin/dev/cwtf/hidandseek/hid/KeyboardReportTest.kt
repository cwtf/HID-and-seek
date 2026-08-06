package dev.cwtf.hidandseek.hid

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardReportTest {

    @Test
    fun `report is eight bytes with modifier first and reserved zero`() {
        val report = KeyboardReport.of(Modifiers.LEFT_SHIFT, Usage.letter('a'))
        val bytes = report.toBytes()

        assertEquals(KeyboardReport.SIZE_BYTES, bytes.size)
        assertEquals(0x02, bytes[0].toInt())
        assertEquals(0x00, bytes[1].toInt(), "byte 1 is reserved and must stay zero")
        assertEquals(0x04, bytes[2].toInt())
        assertContentEquals(ByteArray(5), bytes.copyOfRange(3, 8))
    }

    @Test
    fun `release report is all zeroes`() {
        assertContentEquals(ByteArray(8), KeyboardReport.RELEASE_ALL.toBytes())
        assertTrue(KeyboardReport.RELEASE_ALL.isRelease)
    }

    @Test
    fun `modifier-only report is not a release`() {
        assertFalse(KeyboardReport.of(Modifiers.LEFT_ALT).isRelease)
    }

    @Test
    fun `more than six keys is rejected rather than silently truncated`() {
        assertFailsWith<IllegalArgumentException> {
            KeyboardReport.of(Modifiers.NONE, 4, 5, 6, 7, 8, 9, 10)
        }
    }

    @Test
    fun `six keys is allowed`() {
        val report = KeyboardReport.of(Modifiers.NONE, 4, 5, 6, 7, 8, 9)
        assertEquals(6, report.keys.size)
    }

    @Test
    fun `modifiers combine and are detected`() {
        val combo = Modifiers.LEFT_CTRL + Modifiers.LEFT_SHIFT
        assertTrue(Modifiers.LEFT_CTRL in combo)
        assertTrue(Modifiers.LEFT_SHIFT in combo)
        assertFalse(Modifiers.LEFT_ALT in combo)
        assertEquals(0x03, combo.bits)
    }

    @Test
    fun `modifier names parse including AltGr alias`() {
        assertEquals(Modifiers.RIGHT_ALT, Modifiers.parse(listOf("ALTGR")))
        assertEquals(
            Modifiers.LEFT_CTRL + Modifiers.LEFT_SHIFT,
            Modifiers.parse(listOf("CTRL", "SHIFT")),
        )
    }

    @Test
    fun `unknown modifier name is rejected`() {
        assertFailsWith<IllegalArgumentException> { Modifiers.parse(listOf("HYPER")) }
    }
}
