package dev.cwtf.hidandseek.ui.type

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.Snippet
import dev.cwtf.hidandseek.data.Snippets

/**
 * Saved buffers.
 *
 * The sensitive flag is offered at save time rather than buried in an edit
 * screen, because the moment you are saving a password is the moment you know
 * it is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetSheet(
    snippets: Snippets,
    sheetState: SheetState,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onLoad: (Snippet) -> Unit,
    onSave: (name: String, sensitive: Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Snippets",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            OutlinedButton(
                onClick = { saving = true },
                enabled = canSave,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text("Save current text")
            }

            if (snippets.items.isEmpty()) {
                Text(
                    "Nothing saved yet. Snippets are for the things you type over and over — " +
                        "licence keys, config lines, long passwords.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn {
                items(snippets.items, key = { it.id }) { snippet ->
                    ListItem(
                        headlineContent = { Text(snippet.name) },
                        supportingContent = {
                            Text(
                                if (snippet.sensitive) {
                                    "Hidden — stored encrypted"
                                } else {
                                    snippet.content.lineSequence().first().take(60)
                                },
                            )
                        },
                        leadingContent = if (snippet.sensitive) {
                            { Icon(Icons.Default.Lock, contentDescription = "Sensitive") }
                        } else {
                            null
                        },
                        trailingContent = {
                            IconButton(onClick = { deleting = snippet }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete snippet")
                            }
                        },
                        modifier = Modifier.clickable { onLoad(snippet) },
                    )
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        var sensitive by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = sensitive, onCheckedChange = { sensitive = it })
                        Text("Contains a password or key", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Sensitive snippets are held in the encrypted store, kept out of " +
                            "previews and exports, and block screenshots while loaded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(name, sensitive)
                        saving = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }

    deleting?.let { snippet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${snippet.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(snippet.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
