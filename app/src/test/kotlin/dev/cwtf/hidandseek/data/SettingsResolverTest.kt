package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.HostOs
import dev.cwtf.hidandseek.hid.OverCapAction
import dev.cwtf.hidandseek.hid.TypingProfile
import dev.cwtf.hidandseek.hid.UnmappablePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Precedence between global settings and per-device overrides.
 *
 * Worth testing rather than eyeballing: picking the wrong layout produces text
 * that looks plausible but is wrong, and the failure is silent.
 */
class SettingsResolverTest {

    private val device = DeviceRecord(address = "AA:BB:CC:DD:EE:FF", name = "Workshop PC")

    @Test
    fun `global defaults apply when a device overrides nothing`() {
        val resolved = SettingsResolver.resolve(AppSettings(), device)

        assertEquals(BuiltInLayouts.US_QWERTY, resolved.layout)
        assertEquals(12, resolved.profile.interKeyDelayMs)
        assertEquals(400, resolved.live.settleDelayMs)
        assertIs<UnmappablePolicy.Skip>(resolved.unmappablePolicy)
    }

    @Test
    fun `defaults apply when there is no device at all`() {
        val resolved = SettingsResolver.resolve(AppSettings(), device = null)
        assertEquals(BuiltInLayouts.DEFAULT, resolved.layout)
        assertEquals(TypingProfile.NORMAL.interKeyDelayMs, resolved.profile.interKeyDelayMs)
    }

    @Test
    fun `a device layout overrides the global default`() {
        val settings = AppSettings(typing = TypingSettings(defaultLayoutId = "us"))
        val resolved = SettingsResolver.resolve(settings, device.copy(layoutId = "us_intl"))
        assertEquals(BuiltInLayouts.US_INTERNATIONAL, resolved.layout)
    }

    @Test
    fun `a device profile overrides the global timing`() {
        val settings = AppSettings(typing = TypingSettings(interKeyDelayMs = 5))
        val resolved = SettingsResolver.resolve(settings, device.copy(profileId = "bios"))

        assertEquals(TypingProfile.BIOS.interKeyDelayMs, resolved.profile.interKeyDelayMs)
        assertEquals(TypingProfile.BIOS.keyHoldMs, resolved.profile.keyHoldMs)
    }

    @Test
    fun `humanize is a global preference and survives a device profile`() {
        val settings = AppSettings(typing = TypingSettings(humanize = true))
        val resolved = SettingsResolver.resolve(settings, device.copy(profileId = "safe"))
        assertEquals(true, resolved.profile.humanize)
    }

    @Test
    fun `custom slider values are used verbatim`() {
        val settings = AppSettings(
            typing = TypingSettings(
                profileId = TypingSettings.CUSTOM,
                interKeyDelayMs = 77,
                keyHoldMs = 33,
            ),
        )
        val resolved = SettingsResolver.resolve(settings, device)

        assertEquals(77, resolved.profile.interKeyDelayMs)
        assertEquals(33, resolved.profile.keyHoldMs)
        assertEquals("Custom", resolved.profile.displayName)
    }

    @Test
    fun `an unknown layout id falls back rather than failing to type`() {
        val settings = AppSettings(typing = TypingSettings(defaultLayoutId = "klingon"))
        assertEquals(BuiltInLayouts.DEFAULT, SettingsResolver.resolve(settings, null).layout)
    }

    @Test
    fun `substitute policy carries the configured character`() {
        val settings = AppSettings(
            typing = TypingSettings(
                unmappableAction = UnmappableAction.SUBSTITUTE,
                substituteChar = "#",
            ),
        )
        val policy = SettingsResolver.resolve(settings, device).unmappablePolicy
        assertEquals('#', assertIs<UnmappablePolicy.Substitute>(policy).replacement)
    }

    @Test
    fun `an empty substitute character falls back to a question mark`() {
        val settings = AppSettings(
            typing = TypingSettings(
                unmappableAction = UnmappableAction.SUBSTITUTE,
                substituteChar = "",
            ),
        )
        val policy = SettingsResolver.resolve(settings, device).unmappablePolicy
        assertEquals('?', assertIs<UnmappablePolicy.Substitute>(policy).replacement)
    }

    @Test
    fun `unicode escape takes its host from the device, not the global settings`() {
        val settings = AppSettings(
            typing = TypingSettings(unmappableAction = UnmappableAction.UNICODE_ESCAPE),
        )

        val linux = SettingsResolver.resolve(settings, device.copy(hostOs = HostOsTag.LINUX))
        assertEquals(HostOs.LINUX, assertIs<UnmappablePolicy.UnicodeEscape>(linux.unmappablePolicy).hostOs)

        val untagged = SettingsResolver.resolve(settings, device)
        assertEquals(HostOs.OTHER, assertIs<UnmappablePolicy.UnicodeEscape>(untagged.unmappablePolicy).hostOs)
    }

    @Test
    fun `a device live preset overrides the global live settings`() {
        val settings = AppSettings(live = LiveSettings(settleDelayMs = 400, retractionCap = 64))
        val resolved = SettingsResolver.resolve(settings, device.copy(livePresetId = "careful"))

        assertEquals(800, resolved.live.settleDelayMs)
        assertEquals(16, resolved.live.retractionCap)
    }

    @Test
    fun `live safety choices carry through to the drain config`() {
        val settings = AppSettings(
            live = LiveSettings(
                retractionCap = null,
                overCapAction = OverCapAction.ALWAYS_RETYPE,
                pendingFlushThreshold = null,
                flushOnSpace = false,
            ),
        )
        val live = SettingsResolver.resolve(settings, device).live

        assertEquals(null, live.retractionCap, "unlimited must survive as null, not become 0")
        assertEquals(OverCapAction.ALWAYS_RETYPE, live.overCapAction)
        assertEquals(null, live.pendingFlushThreshold)
        assertEquals(false, live.flushOnSpace)
    }
}

class DeviceRosterTest {

    private val a = DeviceRecord(address = "A", name = "A", lastConnectedAtEpochMs = 100)
    private val b = DeviceRecord(address = "B", name = "B", lastConnectedAtEpochMs = 300)
    private val c = DeviceRecord(address = "C", name = "C", lastConnectedAtEpochMs = null)

    private val roster = DeviceRoster(listOf(a, b, c))

    @Test
    fun `upsert replaces rather than duplicating`() {
        val updated = roster.upsert(a.copy(nickname = "Renamed"))
        assertEquals(3, updated.devices.size)
        assertEquals("Renamed", updated.find("A")?.nickname)
    }

    @Test
    fun `upsert adds an unknown device`() {
        val updated = roster.upsert(DeviceRecord(address = "D", name = "D"))
        assertEquals(4, updated.devices.size)
    }

    @Test
    fun `recency ordering puts never-connected devices last`() {
        assertEquals(listOf("B", "A", "C"), roster.byRecency.map { it.address })
    }

    @Test
    fun `only one device can be the default`() {
        val updated = roster.setDefault("A").setDefault("B")
        assertEquals(listOf("B"), updated.devices.filter { it.isDefault }.map { it.address })
    }

    @Test
    fun `nickname wins over the bluetooth name for display`() {
        assertEquals("A", a.displayName)
        assertEquals("Bench", a.copy(nickname = "Bench").displayName)
        assertEquals("A", a.copy(nickname = "  ").displayName, "blank nickname is not a name")
    }

    @Test
    fun `forgetting removes exactly one device`() {
        val updated = roster.remove("B")
        assertEquals(listOf("A", "C"), updated.devices.map { it.address })
    }
}
