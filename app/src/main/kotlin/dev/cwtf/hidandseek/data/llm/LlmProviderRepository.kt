package dev.cwtf.hidandseek.data.llm

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

private object ProvidersSerializer : Serializer<LlmProviders> {
    override val defaultValue = LlmProviders()

    override suspend fun readFrom(input: InputStream): LlmProviders =
        try {
            json.decodeFromString(LlmProviders.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Could not read providers", e)
        }

    override suspend fun writeTo(t: LlmProviders, output: OutputStream) {
        output.write(json.encodeToString(LlmProviders.serializer(), t).encodeToByteArray())
    }
}

/**
 * Configured LLM endpoints.
 *
 * The store holds no secrets — a provider record carries only the alias of its
 * key, and the key itself lives in [SecretStore]. Deleting a provider deletes
 * its key too, so a removed endpoint leaves nothing behind.
 */
class LlmProviderRepository(
    context: Context,
    scope: CoroutineScope,
    private val secrets: SecretStore,
) {

    private val store: DataStore<LlmProviders> = DataStoreFactory.create(
        serializer = ProvidersSerializer,
        scope = scope,
        produceFile = { context.dataStoreFile("llm_providers.json") },
    )

    val providers: Flow<LlmProviders> = store.data

    suspend fun upsert(provider: LlmProvider) {
        store.updateData { it.upsert(provider) }
    }

    suspend fun setActive(id: String) {
        store.updateData { it.copy(activeProviderId = id) }
    }

    suspend fun delete(id: String) {
        store.updateData { current ->
            current.find(id)?.let { secrets.remove(it.apiKeyAlias) }
            current.remove(id)
        }
    }

    suspend fun setApiKey(provider: LlmProvider, key: String) {
        secrets.put(provider.apiKeyAlias, key)
    }

    fun apiKey(provider: LlmProvider): String? = secrets.get(provider.apiKeyAlias)

    fun hasApiKey(provider: LlmProvider): Boolean = secrets.has(provider.apiKeyAlias)

    suspend fun cacheModels(providerId: String, models: List<LlmModel>) {
        store.updateData { current ->
            val provider = current.find(providerId) ?: return@updateData current
            current.upsert(
                provider.copy(
                    defaultModel = chooseDefaultModel(provider.defaultModel, models),
                    models = models,
                    modelsFetchedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }
}
