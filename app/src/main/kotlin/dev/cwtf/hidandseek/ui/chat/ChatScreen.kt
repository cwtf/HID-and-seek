package dev.cwtf.hidandseek.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.chat.ChatMessage
import dev.cwtf.hidandseek.data.chat.ChatRole
import dev.cwtf.hidandseek.data.chat.MessageSegment
import dev.cwtf.hidandseek.data.chat.parseSegments
import dev.cwtf.hidandseek.hid.TransportState

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenProviderSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by viewModel.messages.collectAsState()
    val transportState by viewModel.transportState.collectAsState()
    val listState = rememberLazyListState()
    val connected = transportState == TransportState.CONNECTED

    LaunchedEffect(messages.size, viewModel.streamingContent) {
        val target = messages.size + if (viewModel.streamingContent != null) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Column(modifier.fillMaxSize()) {
        if (!viewModel.hasProvider) {
            NoProviderCard(onOpenProviderSettings)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    connected = connected,
                    onTypeToHost = viewModel::requestHostSend,
                    onDelete = { viewModel.deleteMessage(message.id) },
                )
            }

            viewModel.streamingContent?.let { partial ->
                item(key = "streaming") {
                    StreamingBubble(partial)
                }
            }
        }

        viewModel.status?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(
            text = viewModel.composerText,
            isStreaming = viewModel.isStreaming,
            onTextChange = viewModel::onComposerChange,
            onSend = viewModel::send,
            onStop = viewModel::stopGenerating,
        )
    }

    viewModel.pendingHostSend?.let { pending ->
        HostSendDialog(
            text = pending,
            onDismiss = viewModel::cancelHostSend,
            onConfirm = viewModel::confirmHostSend,
        )
    }
}

@Composable
private fun NoProviderCard(onOpenProviderSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No LLM configured", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add an OpenAI-compatible provider — OpenRouter, DeepSeek, OpenAI, or a " +
                    "local endpoint — and paste in your API key.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenProviderSettings) { Text("Set up a provider") }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    connected: Boolean,
    onTypeToHost: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val isUser = message.role == ChatRole.USER
    val clipboard = LocalClipboardManager.current
    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                parseSegments(message.content).forEach { segment ->
                    when (segment) {
                        is MessageSegment.Text -> Text(
                            segment.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        is MessageSegment.Code -> CodeBlock(
                            segment = segment,
                            connected = connected,
                            onTypeToHost = onTypeToHost,
                            onCopy = { clipboard.setText(AnnotatedString(segment.code)) },
                        )
                    }
                }

                if (message.incomplete) {
                    Text(
                        "Reply was cut short",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy message",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { onTypeToHost(message.content) },
                        enabled = connected,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = "Type this message to the connected device",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { showActions = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete message",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text("Delete this message?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showActions = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showActions = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A fenced code block with its own actions.
 *
 * The per-block "type to host" is the point of the chat tab: ask for a command,
 * then put exactly that command into the machine that needs it.
 */
@Composable
private fun CodeBlock(
    segment: MessageSegment.Code,
    connected: Boolean,
    onTypeToHost: (String) -> Unit,
    onCopy: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                segment.language ?: "code",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = { onTypeToHost(segment.code) },
                enabled = connected,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "Type this code to the connected device",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Code scrolls inside its own container so a long line never forces the
        // whole conversation sideways.
        Text(
            text = segment.code,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StreamingBubble(partial: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (partial.isEmpty()) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    parseSegments(partial).forEach { segment ->
                        when (segment) {
                            is MessageSegment.Text ->
                                Text(segment.text, style = MaterialTheme.typography.bodyMedium)

                            is MessageSegment.Code -> Text(
                                segment.code,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    isStreaming: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 6,
        )
        FilledIconButton(
            onClick = if (isStreaming) onStop else onSend,
            enabled = isStreaming || text.isNotBlank(),
        ) {
            if (isStreaming) {
                Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.onPrimary))
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/**
 * Confirms what will be typed into the connected machine.
 *
 * Shown by default because this writes into someone else's device: the exact
 * text is visible before anything leaves.
 */
@Composable
private fun HostSendDialog(
    text: String,
    onDismiss: () -> Unit,
    onConfirm: (appendEnter: Boolean, stripIndent: Boolean) -> Unit,
) {
    var appendEnter by remember { mutableStateOf(false) }
    var stripIndent by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type to connected device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${text.length} characters will be typed:")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text.take(400) + if (text.length > 400) "\n…" else "",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
                LabelledCheckbox("Press Enter afterwards", appendEnter) { appendEnter = it }
                LabelledCheckbox(
                    "Remove leading indentation",
                    stripIndent,
                ) { stripIndent = it }
                Text(
                    "Editors that indent automatically will mangle pasted code otherwise.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(appendEnter, stripIndent) }) { Text("Type it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabelledCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
