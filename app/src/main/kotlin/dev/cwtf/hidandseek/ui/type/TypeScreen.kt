package dev.cwtf.hidandseek.ui.type

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.hid.TransportState

@Composable
fun TypeScreen(
    viewModel: TypeViewModel,
    modifier: Modifier = Modifier,
) {
    val transportState by viewModel.transportState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val connected = transportState == TransportState.CONNECTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = viewModel.buffer,
            onValueChange = viewModel::onBufferChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Text to send") },
            placeholder = { Text("Type here, then send it to the connected device") },
            enabled = progress == null,
        )

        // Character counts double as the live-mode pending indicator. Announced
        // politely so it is available without sight of the watermark styling.
        Text(
            text = buildString {
                append("${viewModel.buffer.text.length} chars")
                if (viewModel.mode == SendMode.LIVE) {
                    append(" · ${viewModel.pendingCount} pending")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SendMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = viewModel.mode == entry,
                    onClick = { viewModel.requestMode(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, SendMode.entries.size),
                    enabled = entry == SendMode.STAGED || connected,
                ) {
                    Text(if (entry == SendMode.STAGED) "Staged" else "Live")
                }
            }
        }

        progress?.let {
            LinearProgressIndicator(
                progress = { it.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Sending ${it.charsSent} of ${it.charsTotal}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        viewModel.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::clear, enabled = progress == null) {
                Text("Clear")
            }

            if (viewModel.mode == SendMode.LIVE && viewModel.pendingCount > 0) {
                OutlinedButton(onClick = viewModel::catchUpNow) { Text("Catch up") }
            }

            Row(modifier = Modifier.weight(1f)) {}

            if (progress != null) {
                Button(onClick = viewModel::cancelSend) { Text("Stop") }
            } else {
                Button(
                    onClick = { viewModel.send() },
                    enabled = connected && viewModel.buffer.text.isNotEmpty(),
                ) {
                    Text("Send")
                }
            }
        }
    }

    viewModel.overCapPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveOverCap(retype = false) },
            title = { Text("Large correction needed") },
            text = {
                Text(
                    "Fixing the connected device would delete ${prompt.plan.backspaces} " +
                        "characters there, past the ${prompt.cap}-character limit.\n\n" +
                        "If you have clicked elsewhere on that device, these deletions " +
                        "would remove the wrong text.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveOverCap(retype = true) }) {
                    Text("Retype anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveOverCap(retype = false) }) {
                    Text("Skip and resync")
                }
            },
        )
    }

    if (viewModel.modeSwitchPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissModeSwitch,
            title = { Text("Switch to live typing?") },
            text = { Text("There is already text staged. What should happen to it?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.resolveModeSwitch(ModeSwitchChoice.SEND_FIRST) },
                ) {
                    Text("Send it first")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { viewModel.resolveModeSwitch(ModeSwitchChoice.KEEP_UNSENT) },
                    ) {
                        Text("Keep unsent")
                    }
                    TextButton(
                        onClick = { viewModel.resolveModeSwitch(ModeSwitchChoice.CLEAR) },
                    ) {
                        Text("Clear")
                    }
                }
            },
        )
    }
}
