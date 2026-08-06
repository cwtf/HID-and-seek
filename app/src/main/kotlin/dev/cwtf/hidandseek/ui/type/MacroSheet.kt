package dev.cwtf.hidandseek.ui.type

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cwtf.hidandseek.hid.ConsumerUsage
import dev.cwtf.hidandseek.hid.KeyCombo
import dev.cwtf.hidandseek.hid.Modifiers
import dev.cwtf.hidandseek.hid.SpecialKeys

/**
 * Keys the staging area cannot express.
 *
 * These are what matter when driving a machine rather than writing into one:
 * escaping a menu, tabbing between fields, navigating a BIOS. Modifiers are
 * sticky so a combination is two taps rather than a chord on a touchscreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onKey: (usage: Int, modifiers: Modifiers) -> Unit,
    onCombo: (String) -> Unit,
    onConsumerKey: (Int) -> Unit,
) {
    var ctrl by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var gui by remember { mutableStateOf(false) }

    val modifiers = Modifiers.NONE
        .let { if (ctrl) it + Modifiers.LEFT_CTRL else it }
        .let { if (shift) it + Modifiers.LEFT_SHIFT else it }
        .let { if (alt) it + Modifiers.LEFT_ALT else it }
        .let { if (gui) it + Modifiers.LEFT_GUI else it }

    fun press(usage: Int) {
        onKey(usage, modifiers)
        // Sticky modifiers clear after use, matching how a physical chord
        // behaves — otherwise every later tap silently carries Ctrl.
        ctrl = false; shift = false; alt = false; gui = false
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
                if (modifiers.isEmpty) {
                    "Tap a modifier, then a key."
                } else {
                    "Holding $modifiers — the next key clears it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SheetSection("Editing") {
                SpecialKeys.EDITING.forEach { (label, usage) ->
                    KeyButton(label) { press(usage) }
                }
            }

            SheetSection("Navigation") {
                SpecialKeys.ARROWS.forEach { (label, usage) ->
                    KeyButton(label) { press(usage) }
                }
                SpecialKeys.NAVIGATION.forEach { (label, usage) ->
                    KeyButton(label) { press(usage) }
                }
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

            SheetSection("Combinations") {
                KeyCombo.PRESETS.forEach { (label, combo) ->
                    KeyButton(label) { onCombo(combo) }
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

@Composable
private fun SheetSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
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
private fun KeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 4.dp,
        ),
        modifier = Modifier.widthIn(min = 56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
