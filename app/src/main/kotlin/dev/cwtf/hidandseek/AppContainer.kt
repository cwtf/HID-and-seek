package dev.cwtf.hidandseek

import android.content.Context
import dev.cwtf.hidandseek.bluetooth.HidController
import dev.cwtf.hidandseek.data.AppSettings
import dev.cwtf.hidandseek.data.DeviceRoster
import dev.cwtf.hidandseek.data.DeviceRosterRepository
import dev.cwtf.hidandseek.data.SettingsRepository
import dev.cwtf.hidandseek.data.SettingsResolver
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

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val roster: StateFlow<DeviceRoster> = deviceRosterRepository.roster
        .stateIn(scope, SharingStarted.Eagerly, DeviceRoster())

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
    }
}
