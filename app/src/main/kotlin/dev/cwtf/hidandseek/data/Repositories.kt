package dev.cwtf.hidandseek.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.InputStream
import java.io.OutputStream

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * JSON-backed DataStore serializer.
 *
 * `ignoreUnknownKeys` plus defaults on every field means a settings file
 * written by an older or newer build still loads — a missing field falls back
 * to its documented default rather than wiping the user's configuration.
 */
private class JsonSerializer<T>(
    private val kSerializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T =
        try {
            json.decodeFromString(kSerializer, input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Could not read settings", e)
        }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(kSerializer, t).encodeToByteArray())
    }
}

private inline fun <reified T> createStore(
    context: Context,
    fileName: String,
    defaultValue: T,
    scope: CoroutineScope,
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(serializer<T>(), defaultValue),
    scope = scope,
    produceFile = { context.dataStoreFile(fileName) },
)

class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val store: DataStore<AppSettings> =
        createStore(context, "settings.json", AppSettings(), scope)

    val settings: Flow<AppSettings> = store.data

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    suspend fun updateTyping(transform: (TypingSettings) -> TypingSettings) =
        update { it.copy(typing = transform(it.typing)) }

    suspend fun updateLive(transform: (LiveSettings) -> LiveSettings) =
        update { it.copy(live = transform(it.live)) }

    suspend fun updateConnection(transform: (ConnectionSettings) -> ConnectionSettings) =
        update { it.copy(connection = transform(it.connection)) }

    suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) =
        update { it.copy(appearance = transform(it.appearance)) }

    suspend fun resetTyping() = update { it.copy(typing = TypingSettings()) }

    suspend fun resetLive() = update { it.copy(live = LiveSettings()) }
}

class DeviceRosterRepository(context: Context, scope: CoroutineScope) {

    private val store: DataStore<DeviceRoster> =
        createStore(context, "devices.json", DeviceRoster(), scope)

    val roster: Flow<DeviceRoster> = store.data

    suspend fun upsert(record: DeviceRecord) {
        store.updateData { it.upsert(record) }
    }

    /**
     * Records a successful connection.
     *
     * Adds the device if it is new, so simply using a host is enough to get it
     * into the roster — there is no separate "save this device" step.
     */
    suspend fun recordConnection(address: String, name: String, atEpochMs: Long) {
        store.updateData { roster ->
            val existing = roster.find(address)
            val updated = existing?.copy(lastConnectedAtEpochMs = atEpochMs)
                ?: DeviceRecord(
                    address = address,
                    name = name,
                    lastConnectedAtEpochMs = atEpochMs,
                )
            roster.upsert(updated)
        }
    }

    suspend fun addCharsSent(address: String, chars: Int) {
        if (chars <= 0) return
        store.updateData { roster ->
            val existing = roster.find(address) ?: return@updateData roster
            roster.upsert(existing.copy(charsSent = existing.charsSent + chars))
        }
    }

    suspend fun forget(address: String) {
        store.updateData { it.remove(address) }
    }

    suspend fun forgetAll() {
        store.updateData { DeviceRoster() }
    }

    suspend fun setDefault(address: String) {
        store.updateData { it.setDefault(address) }
    }
}
