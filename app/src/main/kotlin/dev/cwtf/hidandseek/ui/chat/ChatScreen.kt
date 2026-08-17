package dev.cwtf.hidandseek.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.agent.AgentMode
import dev.cwtf.hidandseek.data.agent.ApprovalReason
import dev.cwtf.hidandseek.data.chat.ChatMessage
import dev.cwtf.hidandseek.data.chat.ChatRole
import dev.cwtf.hidandseek.data.chat.MessageAttachment
import dev.cwtf.hidandseek.data.chat.MessageSegment
import dev.cwtf.hidandseek.data.chat.ProcessedImage
import dev.cwtf.hidandseek.data.chat.parseSegments
import dev.cwtf.hidandseek.hid.TransportState
import dev.cwtf.hidandseek.ui.plainTextClipEntry
import kotlinx.coroutines.launch

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
                    canRegenerate = message.role == ChatRole.ASSISTANT &&
                        message.id == messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.id &&
                        !viewModel.isStreaming,
                    onTypeToHost = viewModel::requestHostSend,
                    onDelete = { viewModel.deleteMessage(message.id) },
                    onRegenerate = viewModel::regenerate,
                    onEdit = { viewModel.editAndResend(message) },
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

        AttachmentStrip(
            attachments = viewModel.attachments,
            estimatedTokens = viewModel.estimatedAttachmentTokens,
            modelLacksVision = viewModel.modelLacksVision,
            onRemove = viewModel::removeAttachment,
            onExtractText = viewModel::extractTextFrom,
        )

        AgentModeRow(
            mode = viewModel.agentMode,
            connected = connected,
            modelLacksTools = viewModel.modelLacksToolSupport,
            onModeChange = viewModel::changeAgentMode,
            onStop = viewModel::stopAgentTyping,
        )

        Composer(
            text = viewModel.composerText,
            isStreaming = viewModel.isStreaming,
            canSend = viewModel.composerText.isNotBlank() || viewModel.attachments.isNotEmpty(),
            onTextChange = viewModel::onComposerChange,
            onSend = viewModel::send,
            onStop = viewModel::stopGenerating,
            onAttach = viewModel::attach,
            onPasteImage = viewModel::attachFromClipboard,
        )
    }

    viewModel.pendingHostSend?.let { pending ->
        HostSendDialog(
            text = pending,
            onDismiss = viewModel::cancelHostSend,
            onConfirm = viewModel::confirmHostSend,
        )
    }

    viewModel.pendingApproval?.let { approval ->
        AgentApprovalDialog(
            approval = approval,
            onResolve = viewModel::resolveApproval,
        )
    }
}

/**
 * The agent-typing control.
 *
 * Always visible above the composer, because whether a model can put keystrokes
 * into another machine should never be something you have to go looking for.
 */
@Composable
private fun AgentModeRow(
    mode: AgentMode,
    connected: Boolean,
    modelLacksTools: Boolean,
    onModeChange: (AgentMode) -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (mode == AgentMode.AUTO) {
            // Loud on purpose: Auto means keystrokes reach the machine without
            // another tap, so it should never be ambiguous that it is on.
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Agent can type without asking",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Keyboard,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("Agent typing", style = MaterialTheme.typography.labelMedium)

            AgentMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { onModeChange(entry) },
                    enabled = connected || entry != AgentMode.AUTO,
                    label = {
                        Text(
                            when (entry) {
                                AgentMode.OFF -> "Off"
                                AgentMode.ASK -> "Ask"
                                AgentMode.AUTO -> "Auto"
                            },
                        )
                    },
                )
            }
        }

        if (modelLacksTools && mode != AgentMode.OFF) {
            Text(
                "This model does not support tool calls — agent typing will not work with it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The approval card.
 *
 * Shows the exact text, its length, and why approval was needed. Declining is
 * the dismiss action, so tapping outside never authorises anything.
 */
@Composable
private fun AgentApprovalDialog(
    approval: PendingApproval,
    onResolve: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(false) },
        title = {
            Text(
                if (approval.reason == ApprovalReason.BLOCKLISTED) {
                    "This looks risky — allow it?"
                } else {
                    "Let the agent type this?"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (approval.reason == ApprovalReason.BLOCKLISTED) {
                    Text(
                        "It matched a pattern you asked to be warned about: " +
                            approval.matchedPatterns.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text("${approval.request.text.length} characters will be typed:")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        approval.request.text.take(500),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
                if (approval.request.pressEnter) {
                    Text("Enter will be pressed afterwards.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onResolve(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onResolve(false) }) { Text("Decline") } },
    )
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
    canRegenerate: Boolean,
    onTypeToHost: (String) -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
) {
    val isUser = message.role == ChatRole.USER
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showActions by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf<MessageAttachment?>(null) }

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
                if (message.attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.attachments.forEach { attachment ->
                            SentAttachment(attachment) { zoomed = attachment }
                        }
                    }
                }

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
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(plainTextClipEntry(segment.code))
                                }
                            },
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
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(plainTextClipEntry(message.content))
                            }
                        },
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
                    if (isUser) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit and send again",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (canRegenerate) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Ask again",
                                modifier = Modifier.size(16.dp),
                            )
                        }
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

    zoomed?.let { attachment ->
        AlertDialog(
            onDismissRequest = { zoomed = null },
            confirmButton = { TextButton(onClick = { zoomed = null }) { Text("Close") } },
            text = {
                val bitmap = remember(attachment.id) {
                    runCatching {
                        android.graphics.BitmapFactory
                            .decodeFile(attachment.localPath)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("This image is no longer stored on the device.")
                }
            },
        )
    }
}

/**
 * An image already sent.
 *
 * Purged images leave a placeholder rather than vanishing, so a message still
 * reads as having had a picture attached to it.
 */
@Composable
private fun SentAttachment(attachment: MessageAttachment, onClick: () -> Unit) {
    val bitmap = remember(attachment.id, attachment.deleted) {
        if (attachment.deleted) {
            null
        } else {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(attachment.localPath)?.asImageBitmap()
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        )
    } else {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text("image\ndeleted", style = MaterialTheme.typography.labelSmall)
        }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                    // The shape-morphing expressive indicator: it reads as
                    // "thinking" rather than "loading a fixed amount".
                    LoadingIndicator(Modifier.size(28.dp))
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

/**
 * Pending attachments.
 *
 * Shows the token estimate because images are expensive in a way text is not —
 * a photo can cost more than the whole conversation around it.
 */
@Composable
private fun AttachmentStrip(
    attachments: List<ProcessedImage>,
    estimatedTokens: Int,
    modelLacksVision: Boolean,
    onRemove: (String) -> Unit,
    onExtractText: (ProcessedImage) -> Unit,
) {
    if (attachments.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            attachments.forEach { image ->
                Card {
                    Column(Modifier.padding(6.dp)) {
                        AsyncThumbnail(image)
                        Row {
                            TextButton(onClick = { onExtractText(image) }) { Text("Text") }
                            TextButton(onClick = { onRemove(image.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        Text(
            "${attachments.size} image(s) · about $estimatedTokens tokens",
            style = MaterialTheme.typography.bodySmall,
        )
        if (modelLacksVision) {
            Text(
                "The selected model cannot read images. Use \"Text\" to extract it on-device " +
                    "instead, or pick a vision model in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AsyncThumbnail(image: ProcessedImage) {
    val bitmap = remember(image.id) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(image.file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp),
        )
    } else {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}

@Composable
private fun Composer(
    text: String,
    isStreaming: Boolean,
    canSend: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: (Uri) -> Unit,
    onPasteImage: (Uri?) -> Unit,
) {
    val context = LocalContext.current
    var showAttachMenu by remember { mutableStateOf(false) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    // The Android photo picker grants access to the chosen item only, so no
    // storage or media permission is needed.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onAttach) }

    // Capture is delegated to the system camera app, which is what keeps
    // CAMERA off this app's permission list entirely.
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        if (saved) captureUri?.let(onAttach)
        captureUri = null
        ImageSources.cleanUpCaptures(context)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            IconButton(onClick = { showAttachMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = "Attach a file")
            }
            DropdownMenu(
                expanded = showAttachMenu,
                onDismissRequest = { showAttachMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Photo library") },
                    onClick = {
                        showAttachMenu = false
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Take a photo") },
                    onClick = {
                        showAttachMenu = false
                        val uri = ImageSources.createCaptureUri(context)
                        captureUri = uri
                        takePicture.launch(uri)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Paste image") },
                    onClick = {
                        showAttachMenu = false
                        onPasteImage(ImageSources.clipboardImage(context))
                    },
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 6,
        )
        FilledIconButton(
            onClick = if (isStreaming) onStop else onSend,
            enabled = isStreaming || canSend,
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
