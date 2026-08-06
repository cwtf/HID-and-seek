package dev.cwtf.hidandseek

import android.content.Context
import dev.cwtf.hidandseek.bluetooth.HidConnectionService
import dev.cwtf.hidandseek.bluetooth.HidController
import dev.cwtf.hidandseek.hid.HidTarget
import dev.cwtf.hidandseek.hid.TransportState
import kotlinx.coroutines.delay
import dev.cwtf.hidandseek.data.AppSettings
import dev.cwtf.hidandseek.data.DeviceRoster
import dev.cwtf.hidandseek.data.DeviceRosterRepository
import dev.cwtf.hidandseek.data.SettingsRepository
import dev.cwtf.hidandseek.data.SettingsResolver
import dev.cwtf.hidandseek.data.SnippetRepository
import dev.cwtf.hidandseek.data.TextFileReader
import dev.cwtf.hidandseek.data.chat.ChatRepository
import dev.cwtf.hidandseek.data.chat.ImageProcessor
import dev.cwtf.hidandseek.data.chat.TextRecognizer
import dev.cwtf.hidandseek.data.llm.LlmClient
import dev.cwtf.hidandseek.data.llm.LlmProviderRepository
import dev.cwtf.hidandseek.data.llm.LlmProviders
import dev.cwtf.hidandseek.data.llm.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring.
 *
 * Small enough not to warrant a DI framework yet; revisit if the graph grows
 * past a handful of objects.
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository = SettingsRepository(context, scope)
    val deviceRosterRepository = DeviceRosterRepository(context, scope)
    val hidController = HidController(context)

    val secretStore = SecretStore(context)
    val snippetRepository = SnippetRepository(context, scope, secretStore)
    val textFileReader = TextFileReader(context)
    val llmProviderRepository = LlmProviderRepository(context, scope, secretStore)
    val llmClient = LlmClient()
    val chatRepository = ChatRepository(context)
    val imageProcessor = ImageProcessor(context)
    val textRecognizer = TextRecognizer(context)

    val providers: StateFlow<LlmProviders> = llmProviderRepository.providers
        .stateIn(scope, SharingStarted.Eagerly, LlmProviders())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val roster: StateFlow<DeviceRoster> = deviceRosterRepository.roster
        .stateIn(scope, SharingStarted.Eagerly, DeviceRoster())

    private val appContext = context.applicationContext

    init {
        // Settings, roster, and the active device all feed the same resolution,
        // so a slider change and a device switch take the same path into the
        // typing pipeline rather than each having their own update route.
        scope.launch {
            combine(
                settingsRepository.settings,
                deviceRosterRepository.roster,
                hidController.activeAddress,
            ) { settings, roster, address ->
                SettingsResolver.resolve(settings, address?.let(roster::find))
            }.collect(hidController::applyConfig)
        }

        scope.launch { watchConnection() }
    }

    /**
     * Keeps the foreground service in step with the connection, and reconnects
     * after an unexpected drop.
     *
     * The notification is not optional decoration: a phone silently acting as a
     * keyboard for another machine should be visible and one tap from stopped.
     */
    private suspend fun watchConnection() {
        var wasConnected = false
        var attempt = 0

        hidController.transport.state.collect { state ->
            when (state) {
                TransportState.CONNECTED -> {
                    attempt = 0
                    wasConnected = true
                    val name = hidController.activeAddress.value
                        ?.let { roster.value.find(it)?.displayName }
                        ?: "a device"
                    HidConnectionService.start(appContext, name)
                }

                TransportState.DISCONNECTED, TransportState.REGISTERED -> {
                    HidConnectionService.stop(appContext)
                    if (wasConnected) {
                        wasConnected = false
                        attempt = 0
                        reconnectIfWanted { attempt++ }
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * Retries the last host with exponential backoff.
     *
     * Honours the per-device flag first and the global one second, so a device
     * explicitly marked "do not reconnect" is never chased.
     */
    private suspend fun reconnectIfWanted(onAttempt: () -> Unit) {
        val address = hidController.activeAddress.value ?: return
        val device = roster.value.find(address) ?: return
        if (!device.autoReconnect || !settings.value.connection.autoReconnect) return

        scope.launch {
            var delayMs = 1_000L
            var elapsed = 0L
            while (elapsed < RECONNECT_GIVE_UP_MS) {
                delay(delayMs)
                elapsed += delayMs
                onAttempt()

                if (hidController.transport.state.value == TransportState.CONNECTED) return@launch
                val result = hidController.transport.connect(HidTarget(address, device.name))
                if (result.isSuccess) return@launch

                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_BACKOFF_MS)
            }
        }
    }

    private companion object {
        const val RECONNECT_GIVE_UP_MS = 5 * 60 * 1_000L
        const val RECONNECT_MAX_BACKOFF_MS = 30_000L
    }
}
