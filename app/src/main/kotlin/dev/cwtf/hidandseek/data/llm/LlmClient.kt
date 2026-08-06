package dev.cwtf.hidandseek.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** A tool invocation requested by the model. */
data class WireToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** One turn in a conversation, as sent to the API. */
data class WireMessage(
    val role: String,
    val content: String? = null,
    /** `data:` URIs. Present only on user messages with attachments. */
    val images: List<String> = emptyList(),
    /** Set on assistant turns that called tools. */
    val toolCalls: List<WireToolCall> = emptyList(),
    /** Set on tool-result turns, linking back to the call. */
    val toolCallId: String? = null,
) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
    }
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?) : ChatEvent

    /** The model wants to call tools. Emitted once, at the end of the stream. */
    data class ToolCalls(val calls: List<WireToolCall>) : ChatEvent
    data object Completed : ChatEvent
}

// Typed so the UI can offer a specific recovery rather than a generic error.
class InvalidApiKey : Exception("The provider rejected this API key")
class RateLimited(val retryAfterSeconds: Int?) : Exception("Rate limited by the provider")
class UnknownModel(val model: String) : Exception("Model `$model` was rejected by the provider")
class ModelsUnavailable : Exception("This endpoint does not list models")
class InsecureEndpoint : Exception("Plain HTTP is only allowed for local endpoints")
class ToolsUnsupported : Exception("This model does not support tool calls")
class VisionUnsupported : Exception("This model cannot read images")
class ProviderError(val code: Int, val detail: String) : Exception("Provider error $code: $detail")

private val json = Json { ignoreUnknownKeys = true }
private val jsonMediaType = "application/json".toMediaType()

/**
 * A client for any endpoint speaking the OpenAI Chat Completions protocol.
 *
 * The protocol is standard; the payloads are not. `/models` ranges from a
 * handful of chat models to several hundred entries with rich metadata, or a
 * 404. All of that is normalised here so the UI sees one shape.
 */
class LlmClient {

    private fun httpClient(timeoutSeconds: Int) = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.auth(provider: LlmProvider, apiKey: String?) = apply {
        apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        provider.headers.forEach { (k, v) -> header(k, v) }
    }

    private fun baseUrl(provider: LlmProvider): String {
        val trimmed = provider.baseUrl.trim().trimEnd('/')
        if (trimmed.startsWith("http://") && !provider.allowInsecureHttp) {
            throw InsecureEndpoint()
        }
        return trimmed
    }

    // --- model discovery ----------------------------------------------------

    /**
     * Fetches the provider's model list.
     *
     * Nothing is compiled into the app, so a model released today appears as
     * soon as the provider lists it.
     */
    suspend fun fetchModels(provider: LlmProvider, apiKey: String?): Result<List<LlmModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl(provider)}/models")
                    .get()
                    .auth(provider, apiKey)
                    .build()

                httpClient(provider.timeoutSeconds).newCall(request).execute().use { response ->
                    if (response.code == 404) throw ModelsUnavailable()
                    response.throwIfError()
                    parseModels(response.body?.string().orEmpty())
                }
            }
        }

    internal fun parseModels(body: String): List<LlmModel> {
        val root = json.parseToJsonElement(body)
        val array = when {
            root is JsonArray -> root
            root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
            root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray
            else -> throw ModelsUnavailable()
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null

            val pricing = obj["pricing"] as? JsonObject
            val architecture = obj["architecture"] as? JsonObject
            val modalities = (architecture?.get("input_modalities") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val supported = (obj["supported_parameters"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }

            LlmModel(
                id = id,
                displayName = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it != id },
                contextLength = (obj["context_length"] ?: obj["context_window"])
                    ?.jsonPrimitive?.intOrNull,
                // Providers quote per-token; per-million is the unit humans compare in.
                promptPricePerM = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull
                    ?.toDoubleOrNull()?.times(1_000_000),
                completionPricePerM = pricing?.get("completion")?.jsonPrimitive?.contentOrNull
                    ?.toDoubleOrNull()?.times(1_000_000),
                supportsTools = supported?.let { "tools" in it }
                    ?: obj["supports_tools"]?.jsonPrimitive?.booleanOrNull,
                supportsVision = modalities?.let { "image" in it }
                    ?: obj["supports_vision"]?.jsonPrimitive?.booleanOrNull,
                isFree = id.endsWith(":free"),
            )
        }
    }

    // --- chat ---------------------------------------------------------------

    /**
     * Streams a completion.
     *
     * Emits deltas as they arrive, any tool calls once the stream ends, and
     * completes. Failures are thrown into the flow so callers can keep partial
     * content and mark the message incomplete rather than losing it.
     */
    fun streamChat(
        provider: LlmProvider,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        tools: JsonArray? = null,
    ): Flow<ChatEvent> = callbackFlow {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", provider.temperature)
            put("top_p", provider.topP)
            provider.maxTokens?.let { put("max_tokens", it) }
            tools?.takeIf { it.isNotEmpty() }?.let { put("tools", it) }
            put("messages", buildMessages(provider, messages))
        }

        val request = Request.Builder()
            .url("${baseUrl(provider)}/chat/completions")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .auth(provider, apiKey)
            .build()

        val accumulator = ToolCallAccumulator()

        val source = EventSources.createFactory(httpClient(provider.timeoutSeconds))
            .newEventSource(
                request,
                object : EventSourceListener() {

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        if (data == "[DONE]") {
                            finish()
                            return
                        }
                        runCatching {
                            val chunk = json.parseToJsonElement(data).jsonObject
                            val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            val delta = choice?.get("delta")?.jsonObject

                            delta?.get("content")?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { trySend(ChatEvent.Delta(it)) }

                            (delta?.get("tool_calls") as? JsonArray)?.let(accumulator::consume)

                            (chunk["usage"] as? JsonObject)?.let { usage ->
                                trySend(
                                    ChatEvent.Usage(
                                        usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                                        usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
                                    ),
                                )
                            }
                        }
                        // A malformed chunk mid-stream is not worth losing the
                        // rest of the message over; keep whatever else arrives.
                    }

                    override fun onClosed(eventSource: EventSource) = finish()

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        val error = when {
                            response == null -> t ?: Exception("Connection failed")
                            else -> response.toError(model)
                        }
                        close(error)
                    }

                    private fun finish() {
                        accumulator.result().takeIf { it.isNotEmpty() }?.let {
                            trySend(ChatEvent.ToolCalls(it))
                        }
                        trySend(ChatEvent.Completed)
                        close()
                    }
                },
            )

        awaitClose { source.cancel() }
    }

    private fun buildMessages(provider: LlmProvider, messages: List<WireMessage>): JsonArray =
        buildJsonArray {
            provider.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                addJsonObject {
                    put("role", WireMessage.SYSTEM)
                    put("content", prompt)
                }
            }
            messages.forEach { message ->
                addJsonObject {
                    put("role", message.role)
                    message.toolCallId?.let { put("tool_call_id", it) }

                    when {
                        // Multimodal content array, used when images are attached.
                        message.images.isNotEmpty() -> putJsonArray("content") {
                            message.content?.takeIf { it.isNotBlank() }?.let { text ->
                                addJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            }
                            message.images.forEach { dataUri ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", dataUri)
                                        put("detail", "auto")
                                    }
                                }
                            }
                        }

                        else -> put("content", message.content.orEmpty())
                    }

                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            message.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}

/**
 * Reassembles tool calls from streaming deltas.
 *
 * Providers send a call's name and arguments in fragments across chunks, keyed
 * by index, so the arguments JSON is only valid once the stream ends.
 */
private class ToolCallAccumulator {

    private class Partial(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val byIndex = sortedMapOf<Int, Partial>()

    fun consume(deltas: JsonArray) {
        deltas.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            val partial = byIndex.getOrPut(index) { Partial() }

            obj["id"]?.jsonPrimitive?.contentOrNull?.let { partial.id = it }
            (obj["function"] as? JsonObject)?.let { function ->
                function["name"]?.jsonPrimitive?.contentOrNull?.let { partial.name = it }
                function["arguments"]?.jsonPrimitive?.contentOrNull
                    ?.let { partial.arguments.append(it) }
            }
        }
    }

    fun result(): List<WireToolCall> = byIndex.values.mapNotNull { partial ->
        val name = partial.name ?: return@mapNotNull null
        WireToolCall(
            id = partial.id ?: "call_${name}_${partial.hashCode()}",
            name = name,
            arguments = partial.arguments.toString().ifBlank { "{}" },
        )
    }
}

private fun Response.throwIfError() {
    if (isSuccessful) return
    throw toError(model = null)
}

private fun Response.toError(model: String?): Throwable {
    val detail = runCatching { peekBody(2048).string() }.getOrDefault("")
    return when {
        code == 401 || code == 403 -> InvalidApiKey()
        code == 429 -> RateLimited(header("Retry-After")?.toIntOrNull())
        detail.contains("tool", ignoreCase = true) &&
            detail.contains("support", ignoreCase = true) -> ToolsUnsupported()

        detail.contains("image", ignoreCase = true) &&
            detail.contains("support", ignoreCase = true) -> VisionUnsupported()

        (code == 400 || code == 404) && model != null &&
            detail.contains(model, ignoreCase = true) -> UnknownModel(model)

        else -> ProviderError(code, detail.take(300))
    }
}

/**
 * Model ids that are not chat models.
 *
 * OpenAI's `/models` returns embeddings, speech, and image models alongside
 * chat ones, so the picker filters by id. It is a heuristic, which is why the
 * user can switch it off per provider.
 */
object ModelFilter {

    private val NON_CHAT = listOf(
        "embed", "whisper", "tts", "dall-e", "moderation", "rerank",
        "image", "audio", "speech", "transcribe", "clip", "vae",
    )

    fun isLikelyChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT.none { lower.contains(it) }
    }

    fun apply(models: List<LlmModel>, showAll: Boolean): List<LlmModel> =
        if (showAll) models else models.filter { isLikelyChatModel(it.id) }
}
