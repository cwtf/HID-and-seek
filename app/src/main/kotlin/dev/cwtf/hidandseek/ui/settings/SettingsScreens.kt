package dev.cwtf.hidandseek.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.AppearanceSettings
import dev.cwtf.hidandseek.data.BufferFont
import dev.cwtf.hidandseek.data.MotionIntensity
import dev.cwtf.hidandseek.data.ThemeMode

@Composable
fun SettingsRootScreen(
    viewModel: SettingsViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roster by viewModel.roster.collectAsState()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Devices") {
            SettingsLink(
                title = "Devices",
                subtitle = when (roster.devices.size) {
                    0 -> "No devices yet"
                    1 -> "1 device"
                    else -> "${roster.devices.size} devices"
                },
                icon = Icons.Default.Devices,
                onClick = { onNavigate(SettingsRoutes.DEVICES) },
            )
            SettingsLink(
                title = "Connection",
                subtitle = "Registration, auto-reconnect, notification",
                icon = Icons.Default.Bluetooth,
                onClick = { onNavigate(SettingsRoutes.CONNECTION) },
            )
        }

        SettingsSection("Typing") {
            SettingsLink(
                title = "Keystroke timing",
                subtitle = "Profile, delays, layout, unmappable characters",
                icon = Icons.Default.Keyboard,
                onClick = { onNavigate(SettingsRoutes.TYPING) },
            )
            SettingsLink(
                title = "Live typing",
                subtitle = "Settle delay, flush triggers, retraction safety",
                icon = Icons.Default.Speed,
                onClick = { onNavigate(SettingsRoutes.LIVE) },
            )
        }

        SettingsSection("App") {
            SettingsLink(
                title = "Appearance",
                subtitle = "Theme, colour, motion",
                icon = Icons.Default.Palette,
                onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
            )
            SettingsLink(
                title = "About",
                icon = Icons.Default.Info,
                onClick = { onNavigate(SettingsRoutes.ABOUT) },
            )
        }

        SettingsSection("Not built yet") {
            SettingsLink(title = "LLM providers", subtitle = "See SPEC.md 6.1") {}
            SettingsLink(title = "Agent typing", subtitle = "See SPEC.md 6.3") {}
            SettingsLink(title = "Data and history", subtitle = "See SPEC.md 7.7") {}
        }
    }
}

@Composable
fun ConnectionSettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val connection = settings.connection

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Registration") {
            SettingsSwitch(
                title = "Register on launch",
                subtitle = "Advertise as a keyboard so hosts can find this phone",
                checked = connection.registerOnLaunch,
                onCheckedChange = { v -> viewModel.updateConnection { it.copy(registerOnLaunch = v) } },
            )
            SettingsSwitch(
                title = "Stay registered in background",
                subtitle = "Keeps the phone pairable when the app is closed. Uses more battery.",
                checked = connection.stayRegisteredInBackground,
                onCheckedChange = { v ->
                    viewModel.updateConnection { it.copy(stayRegisteredInBackground = v) }
                },
            )
            OutlinedTextField(
                value = connection.advertisedName,
                onValueChange = { v -> viewModel.updateConnection { it.copy(advertisedName = v) } },
                label = { Text("Name shown to hosts") },
                singleLine = true,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsNote(
                "The advertised name is published when the app registers, so a change " +
                    "takes effect the next time registration happens.",
            )
        }

        SettingsSection("Connecting") {
            SettingsSwitch(
                title = "Connect to default device on launch",
                checked = connection.connectToDefaultOnLaunch,
                onCheckedChange = { v ->
                    viewModel.updateConnection { it.copy(connectToDefaultOnLaunch = v) }
                },
            )
            SettingsSwitch(
                title = "Reconnect automatically",
                subtitle = "Retries with backoff after an unexpected disconnect",
                checked = connection.autoReconnect,
                onCheckedChange = { v -> viewModel.updateConnection { it.copy(autoReconnect = v) } },
            )
            SettingsSwitch(
                title = "Disconnect on screen lock",
                checked = connection.disconnectOnScreenLock,
                onCheckedChange = { v ->
                    viewModel.updateConnection { it.copy(disconnectOnScreenLock = v) }
                },
            )
        }

        SettingsSection("Notification") {
            SettingsSwitch(
                title = "Minimal notification",
                subtitle = "Hide progress and device name while connected",
                checked = connection.minimalNotification,
                onCheckedChange = { v ->
                    viewModel.updateConnection { it.copy(minimalNotification = v) }
                },
            )
            SettingsNote(
                "The notification cannot be turned off entirely. A phone silently typing " +
                    "into another machine should stay visible and one tap from being stopped.",
            )
        }
    }
}

@Composable
fun AppearanceSettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val appearance: AppearanceSettings = settings.appearance

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Theme") {
            SettingsChoiceChips(
                title = "Mode",
                options = ThemeMode.entries,
                selected = appearance.themeMode,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = { v -> viewModel.updateAppearance { it.copy(themeMode = v) } },
                helper = "AMOLED forces surfaces to true black, which costs no power on an OLED panel.",
            )
            SettingsSwitch(
                title = "Dynamic colour",
                subtitle = "Take the palette from your wallpaper",
                checked = appearance.dynamicColor,
                onCheckedChange = { v -> viewModel.updateAppearance { it.copy(dynamicColor = v) } },
            )
        }

        SettingsSection("Motion") {
            SettingsChoiceChips(
                title = "Expressive motion",
                options = MotionIntensity.entries,
                selected = appearance.motionIntensity,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = { v -> viewModel.updateAppearance { it.copy(motionIntensity = v) } },
            )
        }

        SettingsSection("Text area") {
            SettingsChoiceChips(
                title = "Font",
                options = BufferFont.entries,
                selected = appearance.bufferFont,
                label = { if (it == BufferFont.SANS) "Sans" else "Monospace" },
                onSelect = { v -> viewModel.updateAppearance { it.copy(bufferFont = v) } },
                helper = "Monospace makes it easier to spot stray whitespace in commands.",
            )
        }
    }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("HID & Seek", style = MaterialTheme.typography.headlineSmall)
        Text("Version 0.1.0", style = MaterialTheme.typography.bodyMedium)

        SettingsSection("Pairing help") {
            SettingsNote(
                "This phone acts as the keyboard, so pairing starts from the other device, " +
                    "not from here.\n\n" +
                    "Windows: Settings → Bluetooth & devices → Add device → Bluetooth\n" +
                    "macOS: System Settings → Bluetooth → find \"HID & Seek\" → Connect\n" +
                    "Linux (GNOME): Settings → Bluetooth → select \"HID & Seek\"\n\n" +
                    "If nothing appears, make sure this app is open and registered — the " +
                    "connection chip on the Type screen should read \"Ready to pair\".",
            )
        }

        SettingsSection("Troubleshooting") {
            SettingsNote(
                "Wrong characters appearing: the keyboard layout set for the device does not " +
                    "match the layout that device is actually using. Set it per device under " +
                    "Settings → Devices.\n\n" +
                    "Dropped or doubled characters: raise the typing profile to Safe or BIOS " +
                    "for that device.\n\n" +
                    "A key seems stuck: use Release all keys on the Type screen.",
            )
        }
    }
}
