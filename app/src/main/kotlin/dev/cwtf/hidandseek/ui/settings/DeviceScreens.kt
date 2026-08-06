package dev.cwtf.hidandseek.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.DeviceRecord
import dev.cwtf.hidandseek.data.HostOsTag
import dev.cwtf.hidandseek.data.LiveSettings
import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.TypingProfile
import java.text.DateFormat
import java.util.Date

@Composable
fun DevicesScreen(
    viewModel: SettingsViewModel,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roster by viewModel.roster.collectAsState()
    var confirmForgetAll by remember { mutableStateOf(false) }
    var adoptResult by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (roster.devices.isEmpty()) {
            SettingsNote(
                "No devices yet.\n\nDevices are remembered automatically the first time you " +
                    "connect to them. Pairing starts from the other device — this phone " +
                    "appears there as a Bluetooth keyboard.",
            )
        } else {
            SettingsSection("Known devices") {
                roster.byRecency.forEach { device ->
                    ListItem(
                        headlineContent = { Text(device.displayName) },
                        supportingContent = { Text(deviceSummary(device)) },
                        trailingContent = if (device.isDefault) {
                            { Icon(Icons.Default.Star, contentDescription = "Default device") }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable { onOpenDevice(device.address) },
                    )
                }
            }
        }

        SettingsSection("Add") {
            SettingsLink(
                title = "Adopt already-paired devices",
                subtitle = "Bring hosts this phone is already bonded with into the list",
                onClick = {
                    viewModel.adoptBondedDevices()
                    adoptResult = "Checked ${viewModel.bondedCount()} paired device(s)"
                },
            )
            adoptResult?.let { SettingsNote(it) }
        }

        if (roster.devices.isNotEmpty()) {
            SettingsSection("Danger zone") {
                SettingsLink(
                    title = "Forget all devices",
                    subtitle = "Removes every device and its settings",
                    onClick = { confirmForgetAll = true },
                )
            }
        }
    }

    if (confirmForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = { Text("Forget all devices?") },
            text = {
                Text(
                    "This removes ${roster.devices.size} device(s) and their per-device " +
                        "layouts and profiles. Bluetooth pairing itself is not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetAllDevices()
                    confirmForgetAll = false
                }) { Text("Forget all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetAll = false }) { Text("Cancel") }
            },
        )
    }
}

private fun deviceSummary(device: DeviceRecord): String {
    val layout = device.layoutId?.let { BuiltInLayouts.byId(it)?.name } ?: "Default layout"
    val profile = device.profileId?.let { TypingProfile.byId(it)?.displayName } ?: "Default speed"
    val last = device.lastConnectedAtEpochMs
        ?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
        ?: "never connected"
    return "$layout · $profile · $last"
}

@Composable
fun DeviceDetailScreen(
    viewModel: SettingsViewModel,
    address: String,
    onForgotten: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roster by viewModel.roster.collectAsState()
    val device = roster.find(address)
    var confirmForget by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    if (device == null) {
        SettingsNote("This device is no longer in the list.")
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Name") {
            OutlinedTextField(
                value = device.nickname ?: "",
                onValueChange = { v ->
                    viewModel.updateDevice(address) { it.copy(nickname = v.ifBlank { null }) }
                },
                label = { Text("Nickname") },
                placeholder = { Text(device.name) },
                singleLine = true,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsNote("Bluetooth address: ${device.address}")
        }

        SettingsSection("Keyboard layout") {
            SettingsChoiceChips(
                title = "Layout this device uses",
                options = listOf<String?>(null) + BuiltInLayouts.ALL.map { it.id },
                selected = device.layoutId,
                label = { id -> id?.let { BuiltInLayouts.byId(it)?.name } ?: "Use default" },
                onSelect = { id -> viewModel.updateDevice(address) { it.copy(layoutId = id) } },
                helper = "Must match the layout this device is set to. Getting it wrong " +
                    "produces text that looks plausible but is wrong.",
            )
            SettingsLink(
                title = "Test typing",
                subtitle = "Types a sample string so you can check the layout",
                onClick = { viewModel.testTyping { testResult = it } },
            )
            testResult?.let { SettingsNote(it) }
        }

        SettingsSection("Speed") {
            SettingsChoiceChips(
                title = "Typing profile",
                options = listOf<String?>(null) + TypingProfile.PRESETS.map { it.id },
                selected = device.profileId,
                label = { id -> id?.let { TypingProfile.byId(it)?.displayName } ?: "Use default" },
                onSelect = { id -> viewModel.updateDevice(address) { it.copy(profileId = id) } },
                helper = "Use BIOS for firmware screens and KVM switches, which drop input " +
                    "typed at normal speed.",
            )
            SettingsChoiceChips(
                title = "Live typing behaviour",
                options = listOf<String?>(null) + LiveSettings.PRESETS.map { it.presetId },
                selected = device.livePresetId,
                label = { id -> id?.replaceFirstChar(Char::uppercase) ?: "Use default" },
                onSelect = { id -> viewModel.updateDevice(address) { it.copy(livePresetId = id) } },
            )
        }

        SettingsSection("This device is") {
            SettingsChoiceChips(
                title = "Operating system",
                options = HostOsTag.entries,
                selected = device.hostOs,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = { v -> viewModel.updateDevice(address) { it.copy(hostOs = v) } },
                helper = "Used for Unicode escape sequences, which differ per platform.",
            )
        }

        SettingsSection("Behaviour") {
            SettingsSwitch(
                title = "Reconnect automatically",
                checked = device.autoReconnect,
                onCheckedChange = { v ->
                    viewModel.updateDevice(address) { it.copy(autoReconnect = v) }
                },
            )
            SettingsSwitch(
                title = "Default device",
                subtitle = "Connect to this one on launch, when that is enabled",
                checked = device.isDefault,
                onCheckedChange = { v -> if (v) viewModel.setDefaultDevice(address) },
            )
        }

        SettingsSection("Statistics") {
            SettingsNote("Characters typed to this device: ${device.charsSent}")
        }

        SettingsSection("Danger zone") {
            SettingsLink(
                title = "Forget this device",
                subtitle = "Removes it and its settings. Bluetooth pairing is not affected.",
                onClick = { confirmForget = true },
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${device.displayName}?") },
            text = { Text("Its layout, profile, and statistics will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetDevice(address)
                    confirmForget = false
                    onForgotten()
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }
}
