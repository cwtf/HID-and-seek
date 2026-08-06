package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.data.agent.AgentSettings
import dev.cwtf.hidandseek.hid.OverCapAction
import dev.cwtf.hidandseek.hid.ReconnectPolicy
import kotlinx.serialization.Serializable

/**
 * Everything the user can configure.
 *
 * No timing or safety constant in the typing pipeline is hard-coded — each one
 * appears here with the default the spec documents, and per-device overrides
 * (see [DeviceRecord]) win over these.
 */
@Serializable
data class AppSettings(
    val typing: TypingSettings = TypingSettings(),
    val live: LiveSettings = LiveSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val agent: AgentSettings = AgentSettings(),
    val attachments: AttachmentSettings = AttachmentSettings(),
)

/** SPEC 6.2.3 — image handling before anything leaves the device. */
@Serializable
data class AttachmentSettings(
    val maxImagesPerMessage: Int = 4,
    /** Longest edge after downscaling. Beyond this, providers downsample anyway. */
    val maxEdgePx: Int = 1_568,
    val jpegQuality: Int = 85,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
)

// --- keystroke timing (SPEC 7.3.1) ------------------------------------------

@Serializable
enum class UnmappableAction { SKIP, SUBSTITUTE, UNICODE_ESCAPE }

@Serializable
data class TypingSettings(
    /** A preset id, or [CUSTOM] once any individual slider is touched. */
    val profileId: String = "normal",
    val interKeyDelayMs: Int = 12,
    val keyHoldMs: Int = 8,
    val modifierSettleMs: Int = 5,
    val newlineExtraDelayMs: Int = 40,
    val deadKeyExtraDelayMs: Int = 25,
    val humanize: Boolean = false,

    val defaultLayoutId: String = "us",
    val unmappableAction: UnmappableAction = UnmappableAction.SKIP,
    val substituteChar: String = "?",

    /** Warn before sending more than this many characters. 0 disables. */
    val confirmSendOverChars: Int = 1_000,
) {
    companion object {
        const val CUSTOM = "custom"
    }
}

// --- live mode (SPEC 7.3.2) -------------------------------------------------

@Serializable
data class LiveSettings(
    /** A preset id, or [CUSTOM] once any individual value is touched. */
    val presetId: String = "balanced",
    val settleDelayMs: Int = 400,
    val flushOnSpace: Boolean = true,
    val flushOnEnter: Boolean = true,
    /** Null disables the threshold flush entirely. */
    val pendingFlushThreshold: Int? = 120,
    /** Null means unlimited retraction. */
    val retractionCap: Int? = 64,
    val overCapAction: OverCapAction = OverCapAction.ASK,
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.ASK,
    val keepAliveInBackground: Boolean = false,
    val warnOnMidTextEdit: Boolean = true,
) {
    companion object {
        const val CUSTOM = "custom"

        val RESPONSIVE = LiveSettings("responsive", settleDelayMs = 150, retractionCap = 128)
        val BALANCED = LiveSettings("balanced")
        val CAREFUL = LiveSettings("careful", settleDelayMs = 800, retractionCap = 16)

        val PRESETS = listOf(RESPONSIVE, BALANCED, CAREFUL)

        fun preset(id: String): LiveSettings? = PRESETS.firstOrNull { it.presetId == id }
    }
}

// --- connection (SPEC 7.1) --------------------------------------------------

@Serializable
data class ConnectionSettings(
    val registerOnLaunch: Boolean = true,
    val stayRegisteredInBackground: Boolean = false,
    val connectToDefaultOnLaunch: Boolean = false,
    val autoReconnect: Boolean = true,
    val advertisedName: String = "HID & Seek",
    val minimalNotification: Boolean = false,
    val disconnectOnScreenLock: Boolean = false,
)

// --- appearance (SPEC 7.6) --------------------------------------------------

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@Serializable
enum class MotionIntensity { FULL, REDUCED, NONE }

@Serializable
enum class BufferFont { SANS, MONOSPACE }

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val motionIntensity: MotionIntensity = MotionIntensity.FULL,
    val bufferFont: BufferFont = BufferFont.SANS,
    val bufferFontScale: Float = 1.0f,
)
