package dev.cwtf.hidandseek.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cwtf.hidandseek.AppContainer
import dev.cwtf.hidandseek.data.AppSettings
import dev.cwtf.hidandseek.data.AppearanceSettings
import dev.cwtf.hidandseek.data.ConnectionSettings
import dev.cwtf.hidandseek.data.DeviceRecord
import dev.cwtf.hidandseek.data.DeviceRoster
import dev.cwtf.hidandseek.data.LiveSettings
import dev.cwtf.hidandseek.data.TypingSettings
import dev.cwtf.hidandseek.data.agent.AgentAuditEntry
import dev.cwtf.hidandseek.data.agent.AgentSettings
import dev.cwtf.hidandseek.data.chat.ChatStats
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings
    val roster: StateFlow<DeviceRoster> = container.roster

    // --- typing -------------------------------------------------------------

    /**
     * Touching any individual value moves the selector to Custom.
     *
     * The stored slider values are always the effective ones, so a preset is
     * only ever a label describing what the sliders currently say.
     */
    fun updateTyping(transform: (TypingSettings) -> TypingSettings) {
        viewModelScope.launch {
            container.settingsRepository.updateTyping { current ->
                transform(current).copy(profileId = TypingSettings.CUSTOM)
            }
        }
    }

    /** Applies a preset, filling every individual value from it. */
    fun applyTypingProfile(profileId: String) {
        val preset = dev.cwtf.hidandseek.hid.TypingProfile.byId(profileId) ?: return
        viewModelScope.launch {
            container.settingsRepository.updateTyping { current ->
                current.copy(
                    profileId = preset.id,
                    interKeyDelayMs = preset.interKeyDelayMs,
                    keyHoldMs = preset.keyHoldMs,
                    modifierSettleMs = preset.modifierSettleMs,
                    newlineExtraDelayMs = preset.newlineExtraDelayMs,
                    deadKeyExtraDelayMs = preset.deadKeyExtraDelayMs,
                )
            }
        }
    }

    /** Settings that are not timing values and so do not affect the preset. */
    fun updateTypingMeta(transform: (TypingSettings) -> TypingSettings) {
        viewModelScope.launch { container.settingsRepository.updateTyping(transform) }
    }

    fun resetTyping() {
        viewModelScope.launch { container.settingsRepository.resetTyping() }
    }

    // --- live ---------------------------------------------------------------

    fun updateLive(transform: (LiveSettings) -> LiveSettings) {
        viewModelScope.launch {
            container.settingsRepository.updateLive { current ->
                transform(current).copy(presetId = LiveSettings.CUSTOM)
            }
        }
    }

    fun applyLivePreset(presetId: String) {
        val preset = LiveSettings.preset(presetId) ?: return
        viewModelScope.launch {
            container.settingsRepository.updateLive { current ->
                // Preserve the choices the presets do not speak to.
                preset.copy(
                    flushOnSpace = current.flushOnSpace,
                    flushOnEnter = current.flushOnEnter,
                    overCapAction = current.overCapAction,
                    reconnectPolicy = current.reconnectPolicy,
                    keepAliveInBackground = current.keepAliveInBackground,
                    warnOnMidTextEdit = current.warnOnMidTextEdit,
                )
            }
        }
    }

    fun resetLive() {
        viewModelScope.launch { container.settingsRepository.resetLive() }
    }

    // --- agent --------------------------------------------------------------

    val agentAudit: StateFlow<List<AgentAuditEntry>> = container.chatRepository.agentAudit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateAgent(transform: (AgentSettings) -> AgentSettings) {
        viewModelScope.launch { container.settingsRepository.updateAgent(transform) }
    }

    fun clearAgentAudit() {
        viewModelScope.launch { container.chatRepository.clearAgentAudit() }
    }

    // --- chat data ----------------------------------------------------------

    var chatStats by androidx.compose.runtime.mutableStateOf<ChatStats?>(null)
        private set

    fun refreshChatStats() {
        viewModelScope.launch { chatStats = container.chatRepository.stats() }
    }

    fun deleteAllChatHistory() {
        viewModelScope.launch {
            container.chatRepository.deleteAllConversations()
            chatStats = container.chatRepository.stats()
        }
    }

    // --- connection and appearance ------------------------------------------

    fun updateConnection(transform: (ConnectionSettings) -> ConnectionSettings) {
        viewModelScope.launch { container.settingsRepository.updateConnection(transform) }
    }

    fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch { container.settingsRepository.updateAppearance(transform) }
    }

    // --- devices ------------------------------------------------------------

    fun device(address: String): DeviceRecord? = roster.value.find(address)

    fun updateDevice(address: String, transform: (DeviceRecord) -> DeviceRecord) {
        val existing = roster.value.find(address) ?: return
        viewModelScope.launch {
            container.deviceRosterRepository.upsert(transform(existing))
        }
    }

    fun setDefaultDevice(address: String) {
        viewModelScope.launch { container.deviceRosterRepository.setDefault(address) }
    }

    fun forgetDevice(address: String) {
        viewModelScope.launch { container.deviceRosterRepository.forget(address) }
    }

    fun forgetAllDevices() {
        viewModelScope.launch { container.deviceRosterRepository.forgetAll() }
    }

    /** Adopts an already-paired host into the roster without re-pairing. */
    fun adoptBondedDevices() {
        val known = roster.value.devices.map { it.address }.toSet()
        val bonded = container.hidController.transport.bondedDevices()
        viewModelScope.launch {
            bonded.filterNot { it.address in known }.forEach { target ->
                container.deviceRosterRepository.upsert(
                    DeviceRecord(address = target.address, name = target.name),
                )
            }
        }
    }

    fun bondedCount(): Int = container.hidController.transport.bondedDevices().size

    /** Types a sample string so the configured layout can be verified at a glance. */
    fun testTyping(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = container.hidController.typeText("HID & Seek test 123")
            onResult(
                when (result) {
                    is dev.cwtf.hidandseek.bluetooth.TypeResult.Delivered ->
                        "Sent — check what appeared on the device"

                    is dev.cwtf.hidandseek.bluetooth.TypeResult.Partial ->
                        "Stopped after ${result.charsDelivered} characters"

                    is dev.cwtf.hidandseek.bluetooth.TypeResult.Rejected ->
                        result.cause.message ?: "Could not send"
                },
            )
        }
    }
}
