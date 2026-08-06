package dev.cwtf.hidandseek.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.agent.AgentAuditEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun AgentSettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val agent = settings.agent
    val audit by viewModel.agentAudit.collectAsState()
    var editingBlocklist by remember { mutableStateOf(false) }
    var confirmClearAudit by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Mode") {
            SettingsNote(
                "Agent typing always starts at Ask, every time the app opens. Auto can be " +
                    "turned on from the Chat tab for the current session only — it cannot be " +
                    "made the startup mode, and it lapses back to Ask on disconnect or after " +
                    "the expiry below.",
            )
            SettingsSlider(
                title = "Auto mode expires after",
                value = agent.autoExpiryMinutes,
                range = 1..120,
                valueLabel = { "$it min" },
                onValueChange = { v -> viewModel.updateAgent { it.copy(autoExpiryMinutes = v) } },
                onReset = { viewModel.updateAgent { it.copy(autoExpiryMinutes = 15) } },
            )
        }

        SettingsSection("Limits") {
            SettingsSlider(
                title = "Most characters per request",
                value = agent.charCap,
                range = 1..10_000,
                valueLabel = { "$it chars" },
                helper = "Applies in every mode, so a very large paste cannot be approved " +
                    "by accident.",
                onValueChange = { v -> viewModel.updateAgent { it.copy(charCap = v) } },
                onReset = { viewModel.updateAgent { it.copy(charCap = 2_000) } },
            )
            SettingsSlider(
                title = "Requests per reply",
                value = agent.maxCallsPerTurn,
                range = 1..20,
                onValueChange = { v -> viewModel.updateAgent { it.copy(maxCallsPerTurn = v) } },
                onReset = { viewModel.updateAgent { it.copy(maxCallsPerTurn = 3) } },
            )
            SettingsSlider(
                title = "Requests per minute",
                value = agent.maxCallsPerMinute,
                range = 1..60,
                onValueChange = { v -> viewModel.updateAgent { it.copy(maxCallsPerMinute = v) } },
                onReset = { viewModel.updateAgent { it.copy(maxCallsPerMinute = 10) } },
            )
        }

        SettingsSection("Always confirm") {
            SettingsLink(
                title = "Patterns needing confirmation",
                subtitle = "${agent.blocklist.size} patterns",
                onClick = { editingBlocklist = true },
            )
            SettingsNote(
                "Text matching one of these needs a tap even in Auto mode. It is a speed " +
                    "bump on common, unrecoverable commands — not a security boundary, since " +
                    "the same thing can always be phrased differently.",
            )
        }

        SettingsSection("Context") {
            SettingsSwitch(
                title = "Tell the model about the connected device",
                subtitle = "Nickname, operating system, and keyboard layout",
                checked = agent.injectHostContext,
                onCheckedChange = { v -> viewModel.updateAgent { it.copy(injectHostContext = v) } },
            )
            SettingsSlider(
                title = "History sent per request",
                value = agent.historyCharBudget,
                range = 2_000..100_000,
                valueLabel = { "${it / 1000}K chars" },
                helper = "Older messages are left out of the request when the budget is " +
                    "reached. Nothing is deleted — stored history is kept in full.",
                onValueChange = { v -> viewModel.updateAgent { it.copy(historyCharBudget = v) } },
                onReset = { viewModel.updateAgent { it.copy(historyCharBudget = 24_000) } },
            )
        }

        SettingsSection("Your own sends") {
            SettingsSwitch(
                title = "Confirm before typing from chat",
                subtitle = "Show what will be typed when you tap the keyboard icon",
                checked = agent.confirmUserInitiatedSends,
                onCheckedChange = { v ->
                    viewModel.updateAgent { it.copy(confirmUserInitiatedSends = v) }
                },
            )
        }

        SettingsSection("Audit log") {
            if (audit.isEmpty()) {
                SettingsNote("Nothing yet. Every request is recorded here, allowed or not.")
            } else {
                audit.take(50).forEach { AuditRow(it) }
                SettingsLink(
                    title = "Clear audit log",
                    onClick = { confirmClearAudit = true },
                )
            }
        }
    }

    if (editingBlocklist) {
        BlocklistEditor(
            patterns = agent.blocklist,
            onDismiss = { editingBlocklist = false },
            onSave = { patterns ->
                viewModel.updateAgent { it.copy(blocklist = patterns) }
                editingBlocklist = false
            },
        )
    }

    if (confirmClearAudit) {
        AlertDialog(
            onDismissRequest = { confirmClearAudit = false },
            title = { Text("Clear the audit log?") },
            text = { Text("The record of past agent typing requests will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAgentAudit()
                    confirmClearAudit = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAudit = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AuditRow(entry: AgentAuditEntry) {
    ListItem(
        overlineContent = {
            Text(
                "${entry.mode.name} · ${if (entry.approved) "allowed" else "declined"} · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(entry.atEpochMs)),
            )
        },
        headlineContent = { Text(entry.preview, maxLines = 2) },
        supportingContent = { Text("${entry.charCount} chars · ${entry.result}") },
    )
}

@Composable
private fun BlocklistEditor(
    patterns: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(patterns.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Patterns needing confirmation") },
        text = {
            Column {
                Text(
                    "One regular expression per line. An invalid line is ignored rather " +
                        "than breaking the others.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
