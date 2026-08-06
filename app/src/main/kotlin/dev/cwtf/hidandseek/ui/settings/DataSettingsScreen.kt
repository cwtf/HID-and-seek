package dev.cwtf.hidandseek.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

@Composable
fun DataSettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val stats = viewModel.chatStats

    LaunchedEffect(Unit) { viewModel.refreshChatStats() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Chat history") {
            SettingsNote(
                if (stats == null) {
                    "Counting…"
                } else {
                    "${stats.conversations} conversations · ${stats.messages} messages · " +
                        "${(stats.databaseBytes / 1024.0 / 1024.0 * 10).roundToInt() / 10.0} MB"
                },
            )
            SettingsNote(
                "History is kept forever. Nothing expires and nothing is pruned on a timer — " +
                    "deleting is always something you ask for.",
            )
            SettingsLink(
                title = "Delete all chat history",
                subtitle = "Every conversation and message. Cannot be undone.",
                onClick = { confirmDeleteAll = true },
            )
            SettingsNote(
                "Individual messages and conversations can be deleted from the Chat tab.",
            )
        }

        SettingsSection("Privacy") {
            SettingsNote(
                "Messages are sent only to the LLM provider you configure. There is no " +
                    "server in between and no analytics.\n\n" +
                    "API keys are held in the Android keystore, separately from everything " +
                    "else, and are excluded from backups.",
            )
        }
    }

    if (confirmDeleteAll) {
        DeleteAllDialog(
            conversationCount = stats?.conversations ?: 0,
            messageCount = stats?.messages ?: 0,
            onDismiss = { confirmDeleteAll = false },
            onConfirm = {
                viewModel.deleteAllChatHistory()
                confirmDeleteAll = false
            },
        )
    }
}

/**
 * Deleting everything asks the user to type the word.
 *
 * A tap is too easy for something unrecoverable that sits one row below a
 * routine setting.
 */
@Composable
private fun DeleteAllDialog(
    conversationCount: Int,
    messageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all chat history?") },
        text = {
            Column {
                Text(
                    "$conversationCount conversations and $messageCount messages will be " +
                        "permanently deleted, and the database compacted so the content " +
                        "cannot be recovered.",
                )
                Text(
                    "Type \"delete\" to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typed.trim().equals("delete", ignoreCase = true),
            ) { Text("Delete everything") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
