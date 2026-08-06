package dev.cwtf.hidandseek.ui.type

import dev.cwtf.hidandseek.data.HostOsTag

internal enum class ShortcutPlatform(val displayName: String) {
    PC("Windows / Linux"),
    APPLE("macOS / iOS"),
}

internal enum class NavigationMode {
    MOVE,
    SELECT,
}

internal data class MacroShortcut(
    val label: String,
    val combo: String,
    val chord: String,
)

internal fun HostOsTag.toShortcutPlatform(): ShortcutPlatform = when (this) {
    HostOsTag.MACOS, HostOsTag.IOS -> ShortcutPlatform.APPLE
    else -> ShortcutPlatform.PC
}

internal fun navigationShortcuts(
    platform: ShortcutPlatform,
    mode: NavigationMode,
): List<MacroShortcut> {
    val selecting = mode == NavigationMode.SELECT

    fun movement(label: String, key: String, keyLabel: String): MacroShortcut {
        val combo = if (selecting) "shift+$key" else key
        val chord = if (selecting) "Shift + $keyLabel" else keyLabel
        return MacroShortcut(label, combo, chord)
    }

    fun modifiedMovement(
        label: String,
        modifier: String,
        modifierLabel: String,
        key: String,
        keyLabel: String,
    ): MacroShortcut {
        val combo = buildList {
            add(modifier)
            if (selecting) add("shift")
            add(key)
        }.joinToString("+")
        val chord = buildList {
            add(modifierLabel)
            if (selecting) add("Shift")
            add(keyLabel)
        }.joinToString(" + ")
        return MacroShortcut(label, combo, chord)
    }

    val cursor = listOf(
        movement("Left", "left", "Left"),
        movement("Right", "right", "Right"),
        movement("Up", "up", "Up"),
        movement("Down", "down", "Down"),
    )

    val semantic = when (platform) {
        ShortcutPlatform.PC -> listOf(
            modifiedMovement("Word left", "ctrl", "Ctrl", "left", "Left"),
            modifiedMovement("Word right", "ctrl", "Ctrl", "right", "Right"),
            movement("Line start", "home", "Home"),
            movement("Line end", "end", "End"),
            modifiedMovement("Document start", "ctrl", "Ctrl", "home", "Home"),
            modifiedMovement("Document end", "ctrl", "Ctrl", "end", "End"),
        )

        ShortcutPlatform.APPLE -> listOf(
            modifiedMovement("Word left", "option", "Option", "left", "Left"),
            modifiedMovement("Word right", "option", "Option", "right", "Right"),
            modifiedMovement("Line start", "cmd", "Cmd", "left", "Left"),
            modifiedMovement("Line end", "cmd", "Cmd", "right", "Right"),
            modifiedMovement("Document start", "cmd", "Cmd", "up", "Up"),
            modifiedMovement("Document end", "cmd", "Cmd", "down", "Down"),
        )
    }

    return cursor + semantic
}

internal fun editingShortcuts(platform: ShortcutPlatform): List<MacroShortcut> = when (platform) {
    ShortcutPlatform.PC -> listOf(
        MacroShortcut("Cut", "ctrl+x", "Ctrl + X"),
        MacroShortcut("Copy", "ctrl+c", "Ctrl + C"),
        MacroShortcut("Paste", "ctrl+v", "Ctrl + V"),
        MacroShortcut("Undo", "ctrl+z", "Ctrl + Z"),
        MacroShortcut("Redo", "ctrl+shift+z", "Ctrl + Shift + Z"),
        MacroShortcut("Select all", "ctrl+a", "Ctrl + A"),
        MacroShortcut("Find", "ctrl+f", "Ctrl + F"),
        MacroShortcut("Delete word left", "ctrl+backspace", "Ctrl + Backspace"),
        MacroShortcut("Delete word right", "ctrl+delete", "Ctrl + Delete"),
        MacroShortcut("Indent", "tab", "Tab"),
        MacroShortcut("Outdent", "shift+tab", "Shift + Tab"),
    )

    ShortcutPlatform.APPLE -> listOf(
        MacroShortcut("Cut", "cmd+x", "Cmd + X"),
        MacroShortcut("Copy", "cmd+c", "Cmd + C"),
        MacroShortcut("Paste", "cmd+v", "Cmd + V"),
        MacroShortcut("Undo", "cmd+z", "Cmd + Z"),
        MacroShortcut("Redo", "cmd+shift+z", "Cmd + Shift + Z"),
        MacroShortcut("Select all", "cmd+a", "Cmd + A"),
        MacroShortcut("Find", "cmd+f", "Cmd + F"),
        MacroShortcut("Delete word left", "option+backspace", "Option + Backspace"),
        MacroShortcut("Delete word right", "option+delete", "Option + Delete"),
        MacroShortcut("Indent", "tab", "Tab"),
        MacroShortcut("Outdent", "shift+tab", "Shift + Tab"),
    )
}
