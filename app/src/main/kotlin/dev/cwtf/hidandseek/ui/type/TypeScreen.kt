package dev.cwtf.hidandseek.ui.type

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.hid.TransportState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeScreen(
    viewModel: TypeViewModel,
    modifier: Modifier = Modifier,
) {
    val transportState by viewModel.transportState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val connected = transportState == TransportState.CONNECTED
    val clipboard = LocalClipboardManager.current

    var showMacros by remember { mutableStateOf(false) }
    var showSendMenu by remember { mutableStateOf(false) }

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
            visualTransformation = SentPrefixTransformation(
                sentLength = viewModel.sentPrefixLength,
                dimmed = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            ),
        )

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
            OutlinedButton(onClick = { showMacros = true }, enabled = connected) {
                Text("Keys")
            }
            OutlinedButton(onClick = viewModel::showPreview, enabled = viewModel.buffer.text.isNotEmpty()) {
                Text("Preview")
            }

            if (viewModel.mode == SendMode.LIVE && viewModel.pendingCount > 0) {
                OutlinedButton(onClick = viewModel::catchUpNow) { Text("Catch up") }
            }

            Row(modifier = Modifier.weight(1f)) {}

            if (progress != null) {
                Button(onClick = viewModel::cancelSend) { Text("Stop") }
            } else {
                Button(
                    onClick = viewModel::send,
                    enabled = connected && viewModel.buffer.text.isNotEmpty(),
                ) {
                    Text("Send")
                }
            }

            Box {
                IconButton(onClick = { showSendMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More send options")
                }
                SendOptionsMenu(
                    expanded = showSendMenu,
                    viewModel = viewModel,
                    connected = connected,
                    onDismiss = { showSendMenu = false },
                    onSendClipboard = {
                        viewModel.sendClipboard(clipboard.getText()?.text)
                    },
                )
            }
        }
    }

    if (showMacros) {
        MacroSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismiss = { showMacros = false },
            onKey = viewModel::pressKey,
            onCombo = viewModel::pressCombo,
            onConsumerKey = viewModel::pressConsumerKey,
        )
    }

    viewModel.preview?.let { preview ->
        PreviewSheet(
            preview = preview,
            onDismiss = viewModel::dismissPreview,
            onSend = viewModel::send,
            canSend = connected,
        )
    }

    viewModel.pendingConfirm?.let { count ->
        AlertDialog(
            onDismissRequest = viewModel::cancelLongSend,
            title = { Text("Send $count characters?") },
            text = {
                Text(
                    "That is a long send. It will type into the connected device until it " +
                        "finishes or you stop it.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmLongSend) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelLongSend) { Text("Cancel") }
            },
        )
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

@Composable
private fun SendOptionsMenu(
    expanded: Boolean,
    viewModel: TypeViewModel,
    connected: Boolean,
    onDismiss: () -> Unit,
    onSendClipboard: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (viewModel.appendEnter) "✓ Press Enter after" else "Press Enter after") },
            onClick = { viewModel.toggleAppendEnter() },
        )
        DropdownMenuItem(
            text = {
                Text(if (viewModel.stripIndent) "✓ Strip indentation" else "Strip indentation")
            },
            onClick = { viewModel.toggleStripIndent() },
        )
        DropdownMenuItem(
            text = { Text("Send clipboard") },
            enabled = connected,
            onClick = {
                onSendClipboard()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Clear text") },
            onClick = {
                viewModel.clear()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Release all keys") },
            onClick = {
                viewModel.releaseAllKeys()
                onDismiss()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    preview: dev.cwtf.hidandseek.bluetooth.SendPreview,
    canSend: Boolean,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Before sending", style = MaterialTheme.typography.titleLarge)

            Text(
                "${preview.characters} characters · ${preview.strokes} keystrokes · " +
                    "about ${"%.1f".format(preview.estimatedMs / 1000.0)} s",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (preview.skipped > 0) {
                Text(
                    "${preview.skipped} character(s) cannot be typed on this keyboard layout " +
                        "and will be skipped:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    preview.unmappable.joinToString(" ") { it.text },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            preview.warnings.forEach { warning ->
                Text("• $warning", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onDismiss()
                        onSend()
                    },
                    enabled = canSend,
                ) { Text("Send") }
                TextButton(onClick = onDismiss) { Text("Back") }
            }
        }
    }
}

/**
 * Dims the part of the buffer the host already has.
 *
 * Live mode's counter says how much is pending; this shows *where* the boundary
 * is, which is the thing that makes the mode legible at a glance. Offsets are
 * unchanged — only styling differs — so the caret and selection stay correct.
 */
private class SentPrefixTransformation(
    private val sentLength: Int,
    private val dimmed: androidx.compose.ui.graphics.Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (sentLength <= 0) return TransformedText(text, OffsetMapping.Identity)

        val end = sentLength.coerceAtMost(text.length)
        val styled = AnnotatedString.Builder(text).apply {
            addStyle(SpanStyle(color = dimmed), 0, end)
        }.toAnnotatedString()

        return TransformedText(styled, OffsetMapping.Identity)
    }
}
