package dev.cwtf.hidandseek.ui.type

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.DeviceRecord
import dev.cwtf.hidandseek.data.HostOsTag
import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.HidTarget
import kotlinx.coroutines.delay

/**
 * Guided pairing.
 *
 * Pairing runs backwards from what people expect: this phone is the keyboard,
 * so the *host* has to start the pairing, not us. Left to a bare "scan" button
 * users wait for a list that will never populate — hence the explicit steps and
 * the per-platform wording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceSheet(
    sheetState: SheetState,
    knownAddresses: Set<String>,
    bondedDevices: () -> List<HidTarget>,
    onDismiss: () -> Unit,
    onAdopt: (HidTarget, HostOsTag, nickname: String?, layoutId: String) -> Unit,
    onTestTyping: () -> Unit,
    testResult: String?,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(1) }
    var hostOs by remember { mutableStateOf(HostOsTag.WINDOWS) }
    var newDevices by remember { mutableStateOf<List<HidTarget>>(emptyList()) }
    var chosen by remember { mutableStateOf<HidTarget?>(null) }
    var nickname by remember { mutableStateOf("") }
    var layoutId by remember { mutableStateOf(BuiltInLayouts.DEFAULT.id) }

    val discoverable = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { step = 2 }

    // While waiting, watch for a new bond appearing rather than making the user
    // hunt for a refresh button.
    LaunchedEffect(step) {
        if (step != 2) return@LaunchedEffect
        while (true) {
            newDevices = bondedDevices().filterNot { it.address in knownAddresses }
            if (newDevices.isNotEmpty()) break
            delay(1_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add a device", style = MaterialTheme.typography.titleLarge)
            Text("Step $step of 3", style = MaterialTheme.typography.labelMedium)

            when (step) {
                1 -> {
                    Text(
                        "This phone acts as the keyboard, so the pairing has to be started " +
                            "from the computer you want to type into.\n\n" +
                            "First, make this phone visible to it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            discoverable.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(
                                    BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                    120,
                                ),
                            )
                        },
                    ) { Text("Make discoverable") }
                    TextButton(onClick = { step = 2 }) { Text("Already paired — skip") }
                }

                2 -> {
                    Text("Which kind of device?", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            HostOsTag.WINDOWS to "Windows",
                            HostOsTag.MACOS to "macOS",
                            HostOsTag.LINUX to "Linux",
                            HostOsTag.TV to "TV",
                        ).forEach { (tag, label) ->
                            FilterChip(
                                selected = hostOs == tag,
                                onClick = { hostOs = tag },
                                label = { Text(label) },
                            )
                        }
                    }

                    Text(
                        pairingInstructions(hostOs),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (newDevices.isEmpty()) {
                        Text(
                            "Waiting for the pairing to complete…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Newly paired:", style = MaterialTheme.typography.labelLarge)
                        newDevices.forEach { device ->
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = { Text(device.address) },
                                modifier = Modifier.clickable {
                                    chosen = device
                                    nickname = device.name
                                    step = 3
                                },
                            )
                        }
                    }
                }

                3 -> {
                    val device = chosen
                    if (device == null) {
                        Text("No device selected.")
                    } else {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("Name it") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text("Keyboard layout", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BuiltInLayouts.ALL.forEach { layout ->
                                FilterChip(
                                    selected = layoutId == layout.id,
                                    onClick = { layoutId = layout.id },
                                    label = { Text(layout.name) },
                                )
                            }
                        }
                        Text(
                            "This must match what that device is set to — not this phone. " +
                                "The wrong layout produces text that looks plausible but is wrong.",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onAdopt(
                                        device,
                                        hostOs,
                                        nickname.takeIf { it.isNotBlank() },
                                        layoutId,
                                    )
                                    onTestTyping()
                                },
                            ) { Text("Connect and test") }
                            TextButton(onClick = onDismiss) { Text("Done") }
                        }

                        testResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "If the wrong characters appeared, change the layout above " +
                                    "and test again.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun pairingInstructions(host: HostOsTag): String = when (host) {
    HostOsTag.WINDOWS ->
        "On Windows: Settings → Bluetooth & devices → Add device → Bluetooth, " +
            "then pick \"HID & Seek\" from the list."

    HostOsTag.MACOS ->
        "On macOS: System Settings → Bluetooth, find \"HID & Seek\" under nearby " +
            "devices, then Connect."

    HostOsTag.LINUX ->
        "On Linux (GNOME): Settings → Bluetooth, then select \"HID & Seek\". " +
            "With bluetoothctl: scan on, then pair and trust the phone's address."

    HostOsTag.TV ->
        "On the TV: Settings → Remotes & Accessories → Add accessory, then pick " +
            "\"HID & Seek\"."

    else ->
        "Open the device's Bluetooth settings and add a new device — this phone " +
            "appears there as a keyboard called \"HID & Seek\"."
}
