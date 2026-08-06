package dev.cwtf.hidandseek.ui.type

import dev.cwtf.hidandseek.data.HostOsTag
import dev.cwtf.hidandseek.hid.KeyCombo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MacroShortcutsTest {

    @Test
    fun `Apple hosts default to Apple shortcuts`() {
        assertEquals(ShortcutPlatform.APPLE, HostOsTag.MACOS.toShortcutPlatform())
        assertEquals(ShortcutPlatform.APPLE, HostOsTag.IOS.toShortcutPlatform())
        assertEquals(ShortcutPlatform.PC, HostOsTag.WINDOWS.toShortcutPlatform())
        assertEquals(ShortcutPlatform.PC, HostOsTag.LINUX.toShortcutPlatform())
        assertEquals(ShortcutPlatform.PC, HostOsTag.UNKNOWN.toShortcutPlatform())
    }

    @Test
    fun `PC selection shortcuts add shift to every movement`() {
        val shortcuts = navigationShortcuts(ShortcutPlatform.PC, NavigationMode.SELECT)

        assertEquals("shift+up", shortcuts.named("Up").combo)
        assertEquals("ctrl+shift+left", shortcuts.named("Word left").combo)
        assertEquals("shift+home", shortcuts.named("Line start").combo)
        assertEquals("ctrl+shift+end", shortcuts.named("Document end").combo)
    }

    @Test
    fun `Apple movement uses Option for words and Command for boundaries`() {
        val shortcuts = navigationShortcuts(ShortcutPlatform.APPLE, NavigationMode.MOVE)

        assertEquals("option+left", shortcuts.named("Word left").combo)
        assertEquals("cmd+right", shortcuts.named("Line end").combo)
        assertEquals("cmd+up", shortcuts.named("Document start").combo)
        assertEquals("down", shortcuts.named("Down").combo)
    }

    @Test
    fun `editing shortcuts include host aware cut copy and paste`() {
        val pc = editingShortcuts(ShortcutPlatform.PC)
        val apple = editingShortcuts(ShortcutPlatform.APPLE)

        assertEquals("ctrl+x", pc.named("Cut").combo)
        assertEquals("ctrl+c", pc.named("Copy").combo)
        assertEquals("ctrl+v", pc.named("Paste").combo)
        assertEquals("cmd+x", apple.named("Cut").combo)
        assertEquals("cmd+c", apple.named("Copy").combo)
        assertEquals("cmd+v", apple.named("Paste").combo)
        assertEquals("option+backspace", apple.named("Delete word left").combo)
    }

    @Test
    fun `every displayed shortcut is accepted by the HID combo parser`() {
        ShortcutPlatform.entries.forEach { platform ->
            NavigationMode.entries.forEach { mode ->
                navigationShortcuts(platform, mode).forEach { shortcut ->
                    assertNotNull(KeyCombo.parse(shortcut.combo), shortcut.chord)
                }
            }
            editingShortcuts(platform).forEach { shortcut ->
                assertNotNull(KeyCombo.parse(shortcut.combo), shortcut.chord)
            }
        }
    }

    private fun List<MacroShortcut>.named(label: String): MacroShortcut =
        single { it.label == label }
}
