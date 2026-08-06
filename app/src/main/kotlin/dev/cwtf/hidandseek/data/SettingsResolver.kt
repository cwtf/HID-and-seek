package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.HostOs
import dev.cwtf.hidandseek.hid.KeyLayout
import dev.cwtf.hidandseek.hid.LiveTypingConfig
import dev.cwtf.hidandseek.hid.TypingProfile
import dev.cwtf.hidandseek.hid.UnmappablePolicy

/** The settings the typing pipeline actually runs with, after overrides. */
data class ResolvedConfig(
    val profile: TypingProfile,
    val layout: KeyLayout,
    val unmappablePolicy: UnmappablePolicy,
    val live: LiveTypingConfig,
)

/**
 * Merges global settings with the active device's overrides.
 *
 * Pure so the precedence rules are testable without a device, a DataStore, or
 * an Android runtime — which matters because "which layout am I typing with"
 * is the difference between correct text and plausible-looking garbage.
 */
object SettingsResolver {

    fun resolve(settings: AppSettings, device: DeviceRecord?): ResolvedConfig = ResolvedConfig(
        profile = resolveProfile(settings.typing, device),
        layout = resolveLayout(settings.typing, device),
        unmappablePolicy = resolveUnmappablePolicy(settings.typing, device),
        live = resolveLive(settings.live, device),
    )

    /**
     * A per-device profile names a preset. The global profile carries the
     * individual slider values, because selecting a preset in Settings fills
     * those sliders — so the stored values are always the effective ones.
     */
    fun resolveProfile(typing: TypingSettings, device: DeviceRecord?): TypingProfile {
        device?.profileId?.let { id ->
            TypingProfile.byId(id)?.let { return it.copy(humanize = typing.humanize) }
        }
        return TypingProfile(
            id = typing.profileId,
            displayName = TypingProfile.byId(typing.profileId)?.displayName ?: "Custom",
            interKeyDelayMs = typing.interKeyDelayMs,
            keyHoldMs = typing.keyHoldMs,
            modifierSettleMs = typing.modifierSettleMs,
            newlineExtraDelayMs = typing.newlineExtraDelayMs,
            deadKeyExtraDelayMs = typing.deadKeyExtraDelayMs,
            repeatedKeyExtraDelayMs = typing.repeatedKeyExtraDelayMs,
            humanize = typing.humanize,
        )
    }

    fun resolveLayout(typing: TypingSettings, device: DeviceRecord?): KeyLayout {
        val id = device?.layoutId ?: typing.defaultLayoutId
        return BuiltInLayouts.byId(id) ?: BuiltInLayouts.DEFAULT
    }

    fun resolveUnmappablePolicy(
        typing: TypingSettings,
        device: DeviceRecord?,
    ): UnmappablePolicy = when (typing.unmappableAction) {
        UnmappableAction.SKIP -> UnmappablePolicy.Skip

        UnmappableAction.SUBSTITUTE ->
            UnmappablePolicy.Substitute(typing.substituteChar.firstOrNull() ?: '?')

        // The escape sequence is host-specific, so this is one of the few
        // places the device's OS tag changes behaviour rather than just labels.
        UnmappableAction.UNICODE_ESCAPE ->
            UnmappablePolicy.UnicodeEscape(device?.hostOs.toHostOs())
    }

    fun resolveLive(live: LiveSettings, device: DeviceRecord?): LiveTypingConfig {
        val effective = device?.livePresetId?.let { LiveSettings.preset(it) } ?: live
        return LiveTypingConfig(
            settleDelayMs = effective.settleDelayMs,
            flushOnSpace = effective.flushOnSpace,
            flushOnEnter = effective.flushOnEnter,
            pendingFlushThreshold = effective.pendingFlushThreshold,
            retractionCap = effective.retractionCap,
            overCapAction = effective.overCapAction,
        )
    }

    private fun HostOsTag?.toHostOs(): HostOs = when (this) {
        HostOsTag.WINDOWS -> HostOs.WINDOWS
        HostOsTag.MACOS -> HostOs.MACOS
        HostOsTag.LINUX -> HostOs.LINUX
        HostOsTag.ANDROID -> HostOs.ANDROID
        HostOsTag.IOS -> HostOs.IOS
        HostOsTag.TV -> HostOs.TV
        else -> HostOs.OTHER
    }
}
