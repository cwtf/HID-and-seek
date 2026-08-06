package dev.cwtf.hidandseek.ui.type

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import dev.cwtf.hidandseek.data.HostOsTag
import dev.cwtf.hidandseek.hid.ConsumerUsage
import dev.cwtf.hidandseek.hid.KeyCombo
import dev.cwtf.hidandseek.hid.Modifiers
import dev.cwtf.hidandseek.hid.SpecialKeys

/**
 * Sends control, editing, and navigation keys that the staging area cannot express.
 *
 * Sticky modifiers remain available for raw keys. Named shortcuts adapt to the
 * connected host and show their exact chord before the user sends them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroSheet(
    sheetState: SheetState,
    hostOs: HostOsTag,
    onDismiss: () -> Unit,
    onKey: (usage: Int, modifiers: Modifiers) -> Unit,
    onCombo: (String) -> Unit,
    onConsumerKey: (Int) -> Unit,
) {
    var ctrl by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var gui by remember { mutableStateOf(false) }
    var platform by remember(hostOs) { mutableStateOf(hostOs.toShortcutPlatform()) }
    var navigationMode by remember { mutableStateOf(NavigationMode.MOVE) }
    var showMore by remember { mutableStateOf(false) }

    val heldModifiers = Modifiers.NONE
        .let { if (ctrl) it + Modifiers.LEFT_CTRL else it }
        .let { if (shift) it + Modifiers.LEFT_SHIFT else it }
        .let { if (alt) it + Modifiers.LEFT_ALT else it }
        .let { if (gui) it + Modifiers.LEFT_GUI else it }

    fun clearHeldModifiers() {
        ctrl = false
        shift = false
        alt = false
        gui = false
    }

    fun press(usage: Int) {
        onKey(usage, heldModifiers)
        clearHeldModifiers()
    }

    fun pressShortcut(combo: String) {
        val held = buildList {
            if (ctrl) add("ctrl")
            if (shift) add("shift")
            if (alt) add("alt")
            if (gui) add("gui")
        }
        onCombo((held + combo).joinToString("+"))
        clearHeldModifiers()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Keys", style = MaterialTheme.typography.titleLarge)

            SheetSection("Hold") {
                ModifierChip("Ctrl", ctrl) { ctrl = it }
                ModifierChip("Shift", shift) { shift = it }
                ModifierChip("Alt", alt) { alt = it }
                ModifierChip("Win / Cmd", gui) { gui = it }
            }
            Text(
                if (heldModifiers.isEmpty) {
                    "Tap a modifier, then any key or shortcut."
                } else {
                    "Holding $heldModifiers — the next action clears it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SheetSection("Shortcuts for") {
                ShortcutPlatform.entries.forEach { entry ->
                    FilterChip(
                        selected = platform == entry,
                        onClick = { platform = entry },
                        label = { Text(entry.displayName) },
                    )
                }
            }

            SectionTitle("Navigation")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NavigationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = navigationMode == mode,
                        onClick = { navigationMode = mode },
                        label = {
                            Text(if (mode == NavigationMode.MOVE) "Move" else "Select")
                        },
                    )
                }
            }
            Text(
                if (navigationMode == NavigationMode.MOVE) {
                    "Move the caret without changing the selection."
                } else {
                    "Extend the selection while moving the caret."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ShortcutGrid(
                shortcuts = navigationShortcuts(platform, navigationMode),
                onShortcut = { pressShortcut(it.combo) },
            )

            SheetSection("Editing") {
                SpecialKeys.EDITING.forEach { (label, usage) ->
                    KeyButton(label) { press(usage) }
                }
            }
            ShortcutGrid(
                shortcuts = editingShortcuts(platform),
                onShortcut = { pressShortcut(it.combo) },
            )

            TextButton(onClick = { showMore = !showMore }) {
                Text(if (showMore) "Hide more keys" else "More keys")
            }

            if (showMore) {
                SheetSection("Page") {
                    SpecialKeys.NAVIGATION
                        .filter { (label, _) -> label == "PgUp" || label == "PgDn" }
                        .forEach { (label, usage) -> KeyButton(label) { press(usage) } }
                }

                SheetSection("Function") {
                    SpecialKeys.FUNCTION.forEach { (label, usage) ->
                        KeyButton(label) { press(usage) }
                    }
                }

                SheetSection("System") {
                    SpecialKeys.SYSTEM.forEach { (label, usage) ->
                        KeyButton(label) { press(usage) }
                    }
                }

                SheetSection("Advanced") {
                    KeyCombo.PRESETS
                        .filterKeys { it !in MOVED_PRESETS }
                        .forEach { (label, combo) ->
                            KeyButton(label) { pressShortcut(combo) }
                        }
                }

                Text("Media", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConsumerUsage.MEDIA_KEYS.forEach { (label, usage) ->
                        KeyButton(label) { onConsumerKey(usage) }
                    }
                }
            }
        }
    }
}

private val MOVED_PRESETS = setOf("Ctrl+C", "Ctrl+V", "Ctrl+Z")

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun SheetSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    SectionTitle(title)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun ShortcutGrid(
    shortcuts: List<MacroShortcut>,
    onShortcut: (MacroShortcut) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shortcuts.forEach { shortcut ->
            ShortcutButton(shortcut) { onShortcut(shortcut) }
        }
    }
}

@Composable
private fun ModifierChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
    )
}

@Composable
private fun ShortcutButton(shortcut: MacroShortcut, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.widthIn(min = 132.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(shortcut.label, style = MaterialTheme.typography.labelLarge)
            Text(
                shortcut.chord,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.widthIn(min = 56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
