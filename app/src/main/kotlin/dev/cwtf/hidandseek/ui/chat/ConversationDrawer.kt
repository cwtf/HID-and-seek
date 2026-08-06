package dev.cwtf.hidandseek.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.chat.Conversation
import java.text.DateFormat
import java.util.Date

/**
 * The conversation list.
 *
 * History is kept forever, so this is where deletion lives — surfaced rather
 * than buried, because manual deletion is the only thing that ever removes
 * content.
 */
@Composable
fun ConversationDrawer(
    viewModel: ChatViewModel,
    onConversationChosen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by viewModel.conversations.collectAsState()
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var exporting by remember { mutableStateOf<Conversation?>(null) }

    // Export goes through the system file picker, so no storage permission is
    // needed and the user chooses where it lands.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = uri
        if (target != null) {
            runCatching {
                context.contentResolver.openOutputStream(target)?.use { stream ->
                    stream.write(viewModel.exportMarkdown().encodeToByteArray())
                }
            }
        }
        exporting = null
    }

    Column(modifier.fillMaxSize().padding(top = 16.dp)) {
        Text(
            "Conversations",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        ListItem(
            headlineContent = { Text("New chat") },
            leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
            modifier = Modifier.clickable {
                viewModel.newConversation()
                onConversationChosen()
            },
        )

        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = viewModel::onSearchChange,
            label = { Text("Search all messages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        if (viewModel.searchQuery.isNotBlank()) {
            SearchResults(viewModel, onConversationChosen)
            return@Column
        }

        if (conversations.isEmpty()) {
            Text(
                "No conversations yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn {
            items(conversations, key = { it.id }) { conversation ->
                ListItem(
                    headlineContent = { Text(conversation.title) },
                    supportingContent = {
                        Text(
                            "${conversation.messageCount} messages · " +
                                DateFormat.getDateInstance(DateFormat.SHORT)
                                    .format(Date(conversation.updatedAtEpochMs)),
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = {
                                    // Export writes the conversation that is
                                    // open, so select it first.
                                    viewModel.selectConversation(conversation.id)
                                    exporting = conversation
                                    exportLauncher.launch("${conversation.title.take(40)}.md")
                                },
                            ) {
                                Icon(
                                    Icons.Default.FileDownload,
                                    contentDescription = "Export as Markdown",
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.setPinned(conversation.id, !conversation.pinned)
                                },
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = if (conversation.pinned) {
                                        "Unpin"
                                    } else {
                                        "Pin"
                                    },
                                    tint = if (conversation.pinned) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            IconButton(onClick = { deleting = conversation }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        viewModel.selectConversation(conversation.id)
                        onConversationChosen()
                    },
                )
            }
        }
    }

    renaming?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(conversation.id, title)
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${conversation.title}\"?") },
            text = {
                Text(
                    "${conversation.messageCount} messages will be deleted. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(conversation.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchResults(viewModel: ChatViewModel, onChosen: () -> Unit) {
    val results = viewModel.searchResults

    if (results.isEmpty()) {
        Text(
            "Nothing found.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    LazyColumn {
        items(results, key = { it.second.id }) { (conversation, message) ->
            ListItem(
                overlineContent = { Text(conversation.title) },
                headlineContent = {
                    Text(message.content.take(120), maxLines = 2)
                },
                supportingContent = {
                    Text(
                        DateFormat.getDateInstance(DateFormat.SHORT)
                            .format(Date(message.createdAtEpochMs)),
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.selectConversation(conversation.id)
                    viewModel.onSearchChange("")
                    onChosen()
                },
            )
        }
    }
}

