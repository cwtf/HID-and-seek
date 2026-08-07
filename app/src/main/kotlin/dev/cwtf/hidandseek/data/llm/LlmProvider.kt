package dev.cwtf.hidandseek.data.llm

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One model offered by a provider.
 *
 * Every field beyond [id] is nullable because provider `/models` payloads vary
 * enormously — OpenRouter reports pricing and capabilities, DeepSeek reports
 * almost nothing. The UI omits what was not reported rather than inventing it.
 */
@Serializable
data class LlmModel(
    val id: String,
    val displayName: String? = null,
    val contextLength: Int? = null,
    val promptPricePerM: Double? = null,
    val completionPricePerM: Double? = null,
    /** Null means the provider did not say. */
    val supportsTools: Boolean? = null,
    val supportsVision: Boolean? = null,
    val isFree: Boolean = false,
) {
    val label: String get() = displayName ?: id
}

/**
 * An OpenAI-compatible endpoint.
 *
 * The API key is deliberately *not* here — only [apiKeyAlias], which points at
 * an entry in the encrypted store. That keeps the key out of DataStore, out of
 * exports, and out of anything that serialises this record.
 */
@Serializable
data class LlmProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKeyAlias: String = "llm_key_${UUID.randomUUID()}",
    val defaultModel: String = "",
    val models: List<LlmModel> = emptyList(),
    val modelsFetchedAtEpochMs: Long? = null,
    /** Bypasses the non-chat model deny-list in the picker. */
    val showAllModels: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int? = null,
    val systemPrompt: String? = null,
    val timeoutSeconds: Int = 120,
    /** Required for plain-HTTP local endpoints; never allowed for public hosts. */
    val allowInsecureHttp: Boolean = false,
) {
    fun model(id: String): LlmModel? = models.firstOrNull { it.id == id }

    val modelsAreStale: Boolean
        get() = modelsFetchedAtEpochMs?.let {
            System.currentTimeMillis() - it > MODEL_CACHE_TTL_MS
        } ?: true

    companion object {
        const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

@Serializable
data class LlmProviders(
    val providers: List<LlmProvider> = emptyList(),
    val activeProviderId: String? = null,
) {
    val active: LlmProvider?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    fun find(id: String): LlmProvider? = providers.firstOrNull { it.id == id }

    fun upsert(provider: LlmProvider): LlmProviders {
        val index = providers.indexOfFirst { it.id == provider.id }
        return copy(
            providers = if (index >= 0) {
                providers.toMutableList().apply { this[index] = provider }
            } else {
                providers + provider
            },
            activeProviderId = activeProviderId ?: provider.id,
        )
    }

    fun remove(id: String): LlmProviders = copy(
        providers = providers.filterNot { it.id == id },
        activeProviderId = if (activeProviderId == id) null else activeProviderId,
    )
}

/** Keeps an explicit choice, otherwise picks the first discovered chat model. */
internal fun chooseDefaultModel(
    currentDefault: String,
    models: List<LlmModel>,
): String {
    if (currentDefault.isNotBlank()) return currentDefault
    return ModelFilter.apply(models, showAll = false).firstOrNull()?.id
        ?: models.firstOrNull()?.id
        ?: ""
}

/** Starting points with the base URL filled in and the key left blank. */
data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val suggestedModel: String = "",
    val isLocal: Boolean = false,
) {
    fun toProvider() = LlmProvider(
        name = name,
        baseUrl = baseUrl,
        defaultModel = suggestedModel,
        allowInsecureHttp = isLocal,
    )

    companion object {
        val ALL = listOf(
            ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1"),
            ProviderPreset("DeepSeek", "https://api.deepseek.com/v1"),
            ProviderPreset("OpenAI", "https://api.openai.com/v1"),
            ProviderPreset("Groq", "https://api.groq.com/openai/v1"),
            ProviderPreset("Together", "https://api.together.xyz/v1"),
            ProviderPreset("Mistral", "https://api.mistral.ai/v1"),
            ProviderPreset("Ollama (local)", "http://localhost:11434/v1", isLocal = true),
            ProviderPreset("LM Studio (local)", "http://localhost:1234/v1", isLocal = true),
            ProviderPreset("Custom", ""),
        )
    }
}
