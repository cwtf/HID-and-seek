package dev.cwtf.hidandseek.hid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One key press that contributes to producing a character.
 *
 * [dead] marks a dead key — a key that produces nothing on its own and combines
 * with the character that follows it (e.g. `´` then `e` giving `é`). The pacer
 * gives these extra settle time so the host's compose logic keeps up.
 */
data class KeyBinding(
    val usage: Int,
    val modifiers: Modifiers = Modifiers.NONE,
    val dead: Boolean = false,
)

/**
 * A mapping from characters to the key positions that produce them on a
 * particular *host* keyboard layout.
 *
 * The host decodes HID usage codes through whatever layout it is configured
 * for, so this must match the host's setting, not the phone's. Getting it wrong
 * produces plausible-looking but wrong text (`y`/`z` swapped on QWERTZ, symbols
 * scattered on AZERTY), which is why it is a per-device setting.
 *
 * A character maps to a *sequence* of bindings rather than a single one, which
 * is what makes dead-key composition expressible without a special case.
 */
data class KeyLayout(
    val id: String,
    val name: String,
    /** Unicode code point to the key sequence producing it. */
    val bindings: Map<Int, List<KeyBinding>>,
) {

    /** Key sequence for [codePoint], or null if this layout cannot produce it. */
    fun sequenceFor(codePoint: Int): List<KeyBinding>? = bindings[codePoint]

    private val reverse: Map<Long, Int> by lazy {
        buildMap {
            for ((codePoint, sequence) in bindings) {
                val only = sequence.singleOrNull() ?: continue
                if (only.dead) continue
                putIfAbsent(reverseKey(only.modifiers, only.usage), codePoint)
            }
        }
    }

    /**
     * Reverse lookup: what character a host would produce for this report.
     * Used to round-trip recorded reports back into text in tests and dry runs.
     */
    fun decode(modifiers: Modifiers, usage: Int): String? =
        reverse[reverseKey(modifiers, usage)]?.let { String(Character.toChars(it)) }

    private fun reverseKey(modifiers: Modifiers, usage: Int): Long =
        (modifiers.bits.toLong() shl 32) or usage.toLong()

    companion object {

        /**
         * Parses a layout asset.
         *
         * A binding value may be a single object or an array of objects; the
         * array form expresses dead-key composition:
         * ```json
         * { "id": "de_DE", "name": "German (QWERTZ)",
         *   "map": {
         *     "z": { "usage": 28 },
         *     "@": { "usage": 20, "mods": ["RALT"] },
         *     "é": [ { "usage": 46, "dead": true }, { "usage": 8 } ]
         *   } }
         * ```
         */
        fun fromJson(json: String): KeyLayout {
            val root = Json.parseToJsonElement(json).jsonObject
            val id = root.getValue("id").jsonPrimitive.content
            val name = root.getValue("name").jsonPrimitive.content
            val map = root.getValue("map").jsonObject

            val bindings = buildMap<Int, List<KeyBinding>> {
                for ((character, element) in map) {
                    require(character.isNotEmpty()) { "Empty key in layout $id" }
                    val codePoint = character.codePointAt(0)
                    require(Character.charCount(codePoint) == character.length) {
                        "Layout $id maps multi-character string '$character'; " +
                            "keys must be a single code point"
                    }
                    val sequence = when (element) {
                        is JsonArray -> element.jsonArray.map { parseBinding(it.jsonObject) }
                        is JsonObject -> listOf(parseBinding(element.jsonObject))
                        else -> throw IllegalArgumentException(
                            "Layout $id: binding for '$character' must be an object or array",
                        )
                    }
                    require(sequence.isNotEmpty()) {
                        "Layout $id: empty key sequence for '$character'"
                    }
                    put(codePoint, sequence)
                }
            }
            return KeyLayout(id, name, bindings)
        }

        private fun parseBinding(obj: JsonObject): KeyBinding {
            val usage = obj.getValue("usage").jsonPrimitive.content.toInt()
            val mods = obj["mods"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val dead = obj["dead"]?.jsonPrimitive?.content?.toBooleanStrict() ?: false
            return KeyBinding(usage, Modifiers.parse(mods), dead)
        }
    }
}

/**
 * Builder used by the built-in layouts.
 *
 * The US layouts are expressed in Kotlin rather than JSON because they are the
 * reference the others are described against, and a generated `a`..`z` run is
 * both shorter and harder to typo than 100 hand-written JSON entries. Both
 * routes produce the same [KeyLayout], so nothing downstream can tell which
 * source a layout came from.
 */
class KeyLayoutBuilder(private val id: String, private val name: String) {
    private val bindings = mutableMapOf<Int, List<KeyBinding>>()

    fun key(character: Char, usage: Int, modifiers: Modifiers = Modifiers.NONE) {
        bindings[character.code] = listOf(KeyBinding(usage, modifiers))
    }

    /** Binds the unshifted and shifted characters produced by one physical key. */
    fun pair(unshifted: Char, shifted: Char, usage: Int) {
        key(unshifted, usage)
        key(shifted, usage, Modifiers.LEFT_SHIFT)
    }

    /** Binds [character] to a dead key followed by a base key. */
    fun composed(character: Char, dead: KeyBinding, base: KeyBinding) {
        bindings[character.code] = listOf(dead.copy(dead = true), base)
    }

    fun build() = KeyLayout(id, name, bindings.toMap())
}

fun buildKeyLayout(id: String, name: String, block: KeyLayoutBuilder.() -> Unit): KeyLayout =
    KeyLayoutBuilder(id, name).apply(block).build()
