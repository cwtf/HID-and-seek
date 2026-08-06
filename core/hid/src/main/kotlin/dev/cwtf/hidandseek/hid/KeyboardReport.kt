package dev.cwtf.hidandseek.hid

/**
 * The modifier bitmask carried in byte 0 of a boot-protocol keyboard report.
 */
@JvmInline
value class Modifiers(val bits: Int) {

    operator fun plus(other: Modifiers) = Modifiers(bits or other.bits)

    operator fun contains(other: Modifiers) = (bits and other.bits) == other.bits

    val isEmpty: Boolean get() = bits == 0

    override fun toString(): String {
        if (bits == 0) return "none"
        return buildList {
            if (LEFT_CTRL in this@Modifiers) add("LCtrl")
            if (LEFT_SHIFT in this@Modifiers) add("LShift")
            if (LEFT_ALT in this@Modifiers) add("LAlt")
            if (LEFT_GUI in this@Modifiers) add("LGui")
            if (RIGHT_CTRL in this@Modifiers) add("RCtrl")
            if (RIGHT_SHIFT in this@Modifiers) add("RShift")
            if (RIGHT_ALT in this@Modifiers) add("RAlt")
            if (RIGHT_GUI in this@Modifiers) add("RGui")
        }.joinToString("+")
    }

    companion object {
        val NONE = Modifiers(0x00)
        val LEFT_CTRL = Modifiers(0x01)
        val LEFT_SHIFT = Modifiers(0x02)
        val LEFT_ALT = Modifiers(0x04)
        val LEFT_GUI = Modifiers(0x08)
        val RIGHT_CTRL = Modifiers(0x10)
        val RIGHT_SHIFT = Modifiers(0x20)

        /** AltGr on non-US layouts. */
        val RIGHT_ALT = Modifiers(0x40)
        val RIGHT_GUI = Modifiers(0x80)

        /** Parses names used in layout JSON, e.g. `["SHIFT", "RALT"]`. */
        fun parse(names: List<String>): Modifiers =
            names.fold(NONE) { acc, name -> acc + byName(name) }

        private fun byName(name: String): Modifiers = when (name.uppercase()) {
            "CTRL", "LCTRL", "CONTROL" -> LEFT_CTRL
            "SHIFT", "LSHIFT" -> LEFT_SHIFT
            "ALT", "LALT" -> LEFT_ALT
            "GUI", "LGUI", "META", "WIN", "CMD" -> LEFT_GUI
            "RCTRL" -> RIGHT_CTRL
            "RSHIFT" -> RIGHT_SHIFT
            "RALT", "ALTGR" -> RIGHT_ALT
            "RGUI" -> RIGHT_GUI
            else -> throw IllegalArgumentException("Unknown modifier: $name")
        }
    }
}

/**
 * An 8-byte boot-protocol keyboard report (Report ID 1 in the descriptor).
 *
 * Instances are only constructible through [of] and [RELEASE_ALL], which enforces
 * the 6-key rollover limit — a report carrying more than six usages is silently
 * truncated by hosts, so it is rejected here instead.
 */
class KeyboardReport private constructor(
    val modifiers: Modifiers,
    val keys: List<Int>,
) {

    fun toBytes(): ByteArray = ByteArray(SIZE_BYTES).also { out ->
        out[0] = modifiers.bits.toByte()
        out[1] = 0 // reserved
        keys.forEachIndexed { i, usage -> out[2 + i] = usage.toByte() }
    }

    val isRelease: Boolean get() = modifiers.isEmpty && keys.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is KeyboardReport && other.modifiers == modifiers && other.keys == keys

    override fun hashCode(): Int = 31 * modifiers.hashCode() + keys.hashCode()

    override fun toString(): String =
        if (isRelease) "KeyboardReport(release)"
        else "KeyboardReport($modifiers, ${keys.joinToString { "0x%02X".format(it) }})"

    companion object {
        const val REPORT_ID = 1
        const val SIZE_BYTES = 8
        const val MAX_KEYS = 6

        /** The all-zero report. Every key-down must be followed by one of these. */
        val RELEASE_ALL = KeyboardReport(Modifiers.NONE, emptyList())

        fun of(modifiers: Modifiers, vararg usages: Int): KeyboardReport {
            require(usages.size <= MAX_KEYS) {
                "A boot keyboard report holds at most $MAX_KEYS keys, got ${usages.size}"
            }
            require(usages.all { it in 0x00..0xFF }) { "HID usage out of byte range" }
            return KeyboardReport(modifiers, usages.toList())
        }
    }
}

/** A Consumer Control report (Report ID 2) — media and system keys. */
@JvmInline
value class ConsumerReport(val usage: Int) {
    fun toBytes(): ByteArray = byteArrayOf(
        (usage and 0xFF).toByte(),
        ((usage shr 8) and 0xFF).toByte(),
    )

    companion object {
        const val REPORT_ID = 2
        const val SIZE_BYTES = 2
    }
}
