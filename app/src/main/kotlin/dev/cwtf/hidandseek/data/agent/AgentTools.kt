package dev.cwtf.hidandseek.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tools the model may call.
 *
 * Expressed as OpenAI-style function definitions so any provider supporting
 * tool calls can use them. Providers without tool support never see these —
 * they fall back to the fenced-block convention parsed in the view model.
 */
object AgentTools {

    const val TYPE_TO_HOST = "type_to_host"
    const val PRESS_KEYS = "press_keys"
    const val GET_HOST_STATUS = "get_host_status"

    fun definitions(): JsonArray = buildJsonArray {
        add(
            function(
                name = TYPE_TO_HOST,
                description = "Type text on the connected physical device as keyboard input. " +
                    "Use for commands, code, and text the user wants entered on that machine.",
            ) {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The exact text to type")
                }
                putJsonObject("press_enter") {
                    put("type", "boolean")
                    put("description", "Press Enter after the text")
                }
            },
        )
        add(
            function(
                name = PRESS_KEYS,
                description = "Press a key combination on the connected device, " +
                    "for example ctrl+alt+t or cmd+space.",
                required = listOf("combo"),
            ) {
                putJsonObject("combo") {
                    put("type", "string")
                    put("description", "Key combination, e.g. \"ctrl+c\" or \"alt+tab\"")
                }
            },
        )
        add(
            function(
                name = GET_HOST_STATUS,
                description = "Report whether a device is connected, and its keyboard layout " +
                    "and lock-key state. Read-only.",
                required = emptyList(),
            ) {},
        )
    }

    private fun function(
        name: String,
        description: String,
        required: List<String> = listOf("text"),
        properties: JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") { properties() }
                putJsonArray("required") { required.forEach { add(it) } }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads a `type_to_host` call's arguments.
     *
     * Models occasionally emit malformed or truncated JSON; a failure here
     * returns null so the caller reports a tool error rather than typing
     * something unintended into a machine.
     */
    fun parseTypeToHost(callId: String, arguments: String): TypeToHostRequest? =
        runCatching {
            val obj = json.parseToJsonElement(arguments).jsonObject
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return null
            TypeToHostRequest(
                callId = callId,
                text = text,
                pressEnter = obj["press_enter"]?.jsonPrimitive?.booleanOrNull ?: false,
                delayMsOverride = obj["delay_ms"]?.jsonPrimitive?.intOrNull,
            )
        }.getOrNull()

    fun parsePressKeys(callId: String, arguments: String): PressKeysRequest? =
        runCatching {
            val obj = json.parseToJsonElement(arguments).jsonObject
            val combo = obj["combo"]?.jsonPrimitive?.contentOrNull ?: return null
            PressKeysRequest(callId, combo)
        }.getOrNull()
}
