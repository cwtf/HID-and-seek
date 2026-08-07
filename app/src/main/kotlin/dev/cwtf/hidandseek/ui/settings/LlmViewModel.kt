package dev.cwtf.hidandseek.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cwtf.hidandseek.AppContainer
import dev.cwtf.hidandseek.data.llm.LlmModel
import dev.cwtf.hidandseek.data.llm.LlmProvider
import dev.cwtf.hidandseek.data.llm.LlmProviders
import dev.cwtf.hidandseek.data.llm.ModelFilter
import dev.cwtf.hidandseek.data.llm.ModelsUnavailable
import dev.cwtf.hidandseek.data.llm.ProviderPreset
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Result of a Test connection attempt, phrased so each has a next step. */
sealed interface ConnectionTest {
    data object Running : ConnectionTest
    data class Ok(val modelCount: Int, val millis: Long) : ConnectionTest
    data class NoModelList(val message: String) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

class LlmViewModel(private val container: AppContainer) : ViewModel() {

    val providers: StateFlow<LlmProviders> = container.providers

    var connectionTest by mutableStateOf<ConnectionTest?>(null)
        private set

    var modelSearch by mutableStateOf("")

    /** Local echo of the key field; never read back from storage into the UI. */
    var apiKeyDraft by mutableStateOf("")

    private val apiKeySaveJobs = mutableMapOf<String, Job>()

    fun provider(id: String): LlmProvider? = providers.value.find(id)

    fun hasApiKey(provider: LlmProvider) = container.llmProviderRepository.hasApiKey(provider)

    // --- provider lifecycle -------------------------------------------------

    fun addFromPreset(preset: ProviderPreset, onCreated: (String) -> Unit) {
        val provider = preset.toProvider()
        viewModelScope.launch {
            container.llmProviderRepository.upsert(provider)
            onCreated(provider.id)
        }
    }

    fun update(provider: LlmProvider) {
        viewModelScope.launch { container.llmProviderRepository.upsert(provider) }
    }

    fun setActive(id: String) {
        viewModelScope.launch { container.llmProviderRepository.setActive(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.llmProviderRepository.delete(id) }
    }

    fun saveApiKey(provider: LlmProvider) {
        val key = apiKeyDraft.trim()
        apiKeyDraft = ""
        apiKeySaveJobs[provider.id] = viewModelScope.launch {
            container.llmProviderRepository.setApiKey(provider, key)
        }
    }

    // --- model discovery ----------------------------------------------------

    /**
     * Fetches the model list and reports what happened.
     *
     * A missing `/models` endpoint is not an error — plenty of gateways do not
     * implement it — so it resolves to manual entry rather than a red failure.
     */
    fun testConnection(provider: LlmProvider) {
        val pastedKey = apiKeyDraft.trim().takeIf { it.isNotEmpty() }
        if (pastedKey != null) apiKeyDraft = ""
        connectionTest = ConnectionTest.Running
        viewModelScope.launch {
            // Testing immediately after Save must wait for the encrypted write.
            // If the user has only pasted a key, Test also saves and uses it in
            // this same coroutine so there is no stale-key race.
            apiKeySaveJobs.remove(provider.id)?.join()
            val apiKey = try {
                if (pastedKey != null) {
                    container.llmProviderRepository.setApiKey(provider, pastedKey)
                    pastedKey
                } else {
                    container.llmProviderRepository.apiKey(provider)
                }
            } catch (error: Throwable) {
                connectionTest = ConnectionTest.Failed(
                    error.message ?: "Could not save the API key",
                )
                return@launch
            }

            val started = System.currentTimeMillis()
            val result = container.llmClient.fetchModels(
                provider,
                apiKey,
            )
            val elapsed = System.currentTimeMillis() - started

            connectionTest = result.fold(
                onSuccess = { models ->
                    // cacheModels also chooses the first chat-capable model
                    // when this provider does not have a default yet.
                    container.llmProviderRepository.cacheModels(provider.id, models)
                    ConnectionTest.Ok(models.size, elapsed)
                },
                onFailure = { error ->
                    when (error) {
                        is ModelsUnavailable -> ConnectionTest.NoModelList(
                            "Connected, but this endpoint does not list models. " +
                                "Enter a model id by hand.",
                        )

                        else -> ConnectionTest.Failed(error.message ?: "Could not connect")
                    }
                },
            )
        }
    }

    fun refreshModels(provider: LlmProvider) = testConnection(provider)

    fun clearConnectionTest() {
        connectionTest = null
    }

    /** Filtered and searched, ready for the picker. */
    fun visibleModels(provider: LlmProvider, filter: ModelFilterChip): List<LlmModel> {
        val base = ModelFilter.apply(provider.models, provider.showAllModels)
        val searched = if (modelSearch.isBlank()) {
            base
        } else {
            base.filter { it.id.contains(modelSearch, ignoreCase = true) }
        }
        return when (filter) {
            ModelFilterChip.ALL -> searched
            ModelFilterChip.FREE -> searched.filter { it.isFree }
            ModelFilterChip.TOOLS -> searched.filter { it.supportsTools == true }
            ModelFilterChip.VISION -> searched.filter { it.supportsVision == true }
        }
    }

    fun selectModel(provider: LlmProvider, modelId: String) {
        update(provider.copy(defaultModel = modelId))
    }
}

enum class ModelFilterChip { ALL, FREE, TOOLS, VISION }
