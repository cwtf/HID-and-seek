package dev.cwtf.hidandseek.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.data.llm.LlmModel
import dev.cwtf.hidandseek.data.llm.LlmProvider
import dev.cwtf.hidandseek.data.llm.ProviderPreset

@Composable
fun LlmProvidersScreen(
    viewModel: LlmViewModel,
    onOpenProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.providers.collectAsState()
    var showPresets by remember { mutableStateOf(false) }
    var providerToRemove by remember { mutableStateOf<LlmProvider?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (providers.providers.isEmpty()) {
            SettingsNote(
                "No providers yet.\n\nAny endpoint speaking the OpenAI chat protocol works — " +
                    "OpenRouter, DeepSeek, OpenAI, Groq, or a model running on your own " +
                    "machine.",
            )
        } else {
            SettingsSection("Providers") {
                providers.providers.forEach { provider ->
                    ListItem(
                        headlineContent = { Text(provider.name) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(provider.defaultModel.ifBlank { "No model chosen" })
                                    if (!viewModel.hasApiKey(provider)) append(" · No API key")
                                },
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = provider.id == providers.active?.id,
                                onClick = { viewModel.setActive(provider.id) },
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { providerToRemove = provider }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${provider.name}",
                                )
                            }
                        },
                        modifier = Modifier.clickable { onOpenProvider(provider.id) },
                    )
                }
            }
        }

        SettingsSection("Add") {
            SettingsLink(
                title = "Add a provider",
                subtitle = "Start from a preset or enter a custom endpoint",
                onClick = { showPresets = true },
            )
        }

        SettingsNote(
            "Your messages are sent only to the provider you configure here. " +
                "There is no server in between.",
        )
    }

    if (showPresets) {
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("Add a provider") },
            text = {
                LazyColumn {
                    items(ProviderPreset.ALL) { preset ->
                        ListItem(
                            headlineContent = { Text(preset.name) },
                            supportingContent = {
                                Text(preset.baseUrl.ifBlank { "Enter your own endpoint" })
                            },
                            modifier = Modifier.clickable {
                                showPresets = false
                                viewModel.addFromPreset(preset, onOpenProvider)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) { Text("Cancel") }
            },
        )
    }

    providerToRemove?.let { provider ->
        AlertDialog(
            onDismissRequest = { providerToRemove = null },
            title = { Text("Remove ${provider.name}?") },
            text = {
                Text("This removes the provider configuration and its stored API key.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(provider.id)
                    providerToRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { providerToRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun LlmProviderEditorScreen(
    viewModel: LlmViewModel,
    providerId: String,
    onOpenModelPicker: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.providers.collectAsState()
    val provider = providers.find(providerId)
    var confirmDelete by remember { mutableStateOf(false) }

    if (provider == null) {
        SettingsNote("This provider no longer exists.")
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Endpoint") {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { viewModel.update(provider.copy(name = it)) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { viewModel.update(provider.copy(baseUrl = it.trim())) },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (provider.baseUrl.startsWith("http://")) {
                SettingsSwitch(
                    title = "Allow plain HTTP",
                    subtitle = "Only for endpoints on your own machine or network",
                    checked = provider.allowInsecureHttp,
                    onCheckedChange = { viewModel.update(provider.copy(allowInsecureHttp = it)) },
                )
            }
        }

        SettingsSection("API key") {
            OutlinedTextField(
                value = viewModel.apiKeyDraft,
                onValueChange = { viewModel.apiKeyDraft = it },
                label = {
                    Text(if (viewModel.hasApiKey(provider)) "Replace key" else "API key")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.saveApiKey(provider) },
                    enabled = viewModel.apiKeyDraft.isNotBlank(),
                ) { Text("Save key") }
                TextButton(onClick = { viewModel.testConnection(provider) }) {
                    Text("Test connection")
                }
            }
            ConnectionTestResult(viewModel)
            SettingsNote(
                "Keys are held in the Android keystore, separately from everything else, " +
                    "and are never written to logs, exports, or backups.",
            )
        }

        SettingsSection("Model") {
            SettingsLink(
                title = "Model",
                subtitle = provider.defaultModel.ifBlank { "None chosen" },
                onClick = onOpenModelPicker,
            )
            SettingsSwitch(
                title = "Show non-chat models",
                subtitle = "Include embeddings, speech, and image models in the picker",
                checked = provider.showAllModels,
                onCheckedChange = { viewModel.update(provider.copy(showAllModels = it)) },
            )
        }

        SettingsSection("Generation") {
            SettingsSlider(
                title = "Temperature",
                value = (provider.temperature * 100).toInt(),
                range = 0..200,
                valueLabel = { "%.2f".format(it / 100f) },
                onValueChange = { viewModel.update(provider.copy(temperature = it / 100f)) },
                onReset = { viewModel.update(provider.copy(temperature = 0.7f)) },
            )
            SettingsSlider(
                title = "Top P",
                value = (provider.topP * 100).toInt(),
                range = 0..100,
                valueLabel = { "%.2f".format(it / 100f) },
                onValueChange = { viewModel.update(provider.copy(topP = it / 100f)) },
                onReset = { viewModel.update(provider.copy(topP = 1f)) },
            )
            OutlinedTextField(
                value = provider.systemPrompt.orEmpty(),
                onValueChange = {
                    viewModel.update(provider.copy(systemPrompt = it.ifBlank { null }))
                },
                label = { Text("System prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        SettingsSection("Danger zone") {
            SettingsLink(
                title = "Delete this provider",
                subtitle = "Also deletes its stored API key",
                onClick = { confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${provider.name}?") },
            text = { Text("Its API key will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(provider.id)
                    confirmDelete = false
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConnectionTestResult(viewModel: LlmViewModel) {
    when (val test = viewModel.connectionTest) {
        null -> Unit
        ConnectionTest.Running -> SettingsNote("Testing…")
        is ConnectionTest.Ok -> SettingsNote(
            "Connected — ${test.modelCount} models · ${test.millis} ms",
        )

        is ConnectionTest.NoModelList -> SettingsNote(test.message)
        is ConnectionTest.Failed -> SettingsNote(test.message)
    }
}

/**
 * The model picker.
 *
 * A searchable full-screen list rather than a dropdown, because providers range
 * from six models to several hundred and a spinner is unusable at that end.
 */
@Composable
fun ModelPickerScreen(
    viewModel: LlmViewModel,
    providerId: String,
    onChosen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.providers.collectAsState()
    val provider = providers.find(providerId)
    var filter by remember { mutableStateOf(ModelFilterChip.ALL) }
    var manualEntry by remember { mutableStateOf(false) }

    if (provider == null) {
        SettingsNote("This provider no longer exists.")
        return
    }

    val models = viewModel.visibleModels(provider, filter)

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = viewModel.modelSearch,
            onValueChange = { viewModel.modelSearch = it },
            label = { Text("Search models") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelFilterChip.entries.forEach { chip ->
                FilterChip(
                    selected = filter == chip,
                    onClick = { filter = chip },
                    label = {
                        Text(
                            when (chip) {
                                ModelFilterChip.ALL -> "All"
                                ModelFilterChip.FREE -> "Free"
                                ModelFilterChip.TOOLS -> "Tools"
                                ModelFilterChip.VISION -> "Vision"
                            },
                        )
                    },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = { viewModel.refreshModels(provider) },
                label = { Text("Refresh") },
            )
            AssistChip(
                onClick = { manualEntry = true },
                label = { Text("Enter model id") },
            )
        }

        if (models.isEmpty()) {
            SettingsNote(
                if (provider.models.isEmpty()) {
                    "No models loaded yet. Tap Refresh, or enter a model id by hand if this " +
                        "endpoint does not list them."
                } else {
                    "Nothing matches that search."
                },
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(models, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    selected = model.id == provider.defaultModel,
                    onClick = {
                        viewModel.selectModel(provider, model.id)
                        onChosen()
                    },
                )
            }
        }
    }

    if (manualEntry) {
        var typed by remember { mutableStateOf(provider.defaultModel) }
        AlertDialog(
            onDismissRequest = { manualEntry = false },
            title = { Text("Enter a model id") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("Model id") },
                    )
                    Text(
                        "Not checked against the list — a model too new to appear there, or " +
                            "one only your account can see, will still work.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.selectModel(provider, typed.trim())
                    manualEntry = false
                    onChosen()
                }) { Text("Use it") }
            },
            dismissButton = {
                TextButton(onClick = { manualEntry = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelRow(model: LlmModel, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(model.label) },
        supportingContent = {
            // Only what the provider actually reported — nothing is guessed.
            val parts = buildList {
                model.contextLength?.let { add("${it / 1000}K ctx") }
                model.promptPricePerM?.let { prompt ->
                    val completion = model.completionPricePerM
                    add(
                        if (completion != null) {
                            "$%.2f/$%.2f per M".format(prompt, completion)
                        } else {
                            "$%.2f per M".format(prompt)
                        },
                    )
                }
                if (model.supportsTools == true) add("tools")
                if (model.supportsVision == true) add("vision")
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
        },
        trailingContent = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "Selected") }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
