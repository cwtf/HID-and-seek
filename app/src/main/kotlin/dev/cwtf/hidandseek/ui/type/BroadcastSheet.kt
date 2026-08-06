package dev.cwtf.hidandseek.ui.type

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.DeviceRecord

/**
 * Sends the same text to several devices, one after another.
 *
 * The wording is deliberate about being sequential: the platform holds one HID
 * connection at a time, so this is a queue rather than a simultaneous send, and
 * a user expecting the latter would be surprised by how long it takes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastSheet(
    devices: List<DeviceRecord>,
    state: BroadcastState?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onStart: (List<DeviceRecord>) -> Unit,
    onAbort: () -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Send to several devices", style = MaterialTheme.typography.titleLarge)

            if (state == null) {
                Text(
                    "Each device is connected, typed into, and disconnected in turn. " +
                        "Only one can be connected at a time, so this takes as long as all " +
                        "of them together.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                LazyColumn(Modifier.fillMaxWidth()) {
                    items(devices, key = { it.address }) { device ->
                        ListItem(
                            headlineContent = { Text(device.displayName) },
                            supportingContent = { Text(device.address) },
                            leadingContent = {
                                Checkbox(
                                    checked = device.address in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) {
                                            selected + device.address
                                        } else {
                                            selected - device.address
                                        }
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                selected = if (device.address in selected) {
                                    selected - device.address
                                } else {
                                    selected + device.address
                                }
                            },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStart(devices.filter { it.address in selected }) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text("Send to ${selected.size}")
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            } else {
                state.results.forEach { result ->
                    ListItem(
                        headlineContent = { Text(result.name) },
                        supportingContent = { Text(result.status) },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.finished) {
                        TextButton(onClick = onAbort, enabled = !state.aborted) {
                            Text(if (state.aborted) "Stopping…" else "Stop after this one")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(if (state.finished) "Done" else "Hide")
                    }
                }
            }
        }
    }
}
