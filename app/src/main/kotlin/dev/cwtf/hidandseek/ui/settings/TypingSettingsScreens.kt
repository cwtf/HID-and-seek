package dev.cwtf.hidandseek.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.cwtf.hidandseek.data.LiveSettings
import dev.cwtf.hidandseek.data.SettingsResolver
import dev.cwtf.hidandseek.data.TypingSettings
import dev.cwtf.hidandseek.data.UnmappableAction
import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.OverCapAction
import dev.cwtf.hidandseek.hid.ReconnectPolicy
import dev.cwtf.hidandseek.hid.ReportScheduler
import dev.cwtf.hidandseek.hid.LayoutMapper
import kotlin.math.roundToInt

/** SPEC 7.3.1 — the five per-keystroke intervals, plus layout and loss policy. */
@Composable
fun TypingSettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val typing = settings.typing

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Profile") {
            SettingsChoiceChips(
                title = "Typing profile",
                options = dev.cwtf.hidandseek.hid.TypingProfile.PRESETS.map { it.id } +
                    TypingSettings.CUSTOM,
                selected = typing.profileId,
                label = { id ->
                    dev.cwtf.hidandseek.hid.TypingProfile.byId(id)?.displayName ?: "Custom"
                },
                onSelect = { id -> if (id != TypingSettings.CUSTOM) viewModel.applyTypingProfile(id) },
                helper = "Selecting a profile fills the sliders below. Moving any slider " +
                    "switches to Custom.",
            )
            ThroughputReadout(typing)
        }

        SettingsSection("Delays") {
            SettingsSlider(
                title = "Inter-key delay",
                value = typing.interKeyDelayMs,
                range = 0..200,
                valueLabel = { "$it ms" },
                helper = "Gap between one keystroke finishing and the next starting. " +
                    "The main speed control.",
                onValueChange = { v -> viewModel.updateTyping { it.copy(interKeyDelayMs = v) } },
                onReset = { viewModel.updateTyping { it.copy(interKeyDelayMs = 12) } },
            )
            SettingsSlider(
                title = "Key hold time",
                value = typing.keyHoldMs,
                range = 0..50,
                valueLabel = { "$it ms" },
                helper = "How long a key stays pressed. Some BIOS and KVM firmware ignores " +
                    "keys released too quickly.",
                onValueChange = { v -> viewModel.updateTyping { it.copy(keyHoldMs = v) } },
                onReset = { viewModel.updateTyping { it.copy(keyHoldMs = 8) } },
            )
            SettingsSlider(
                title = "Modifier settle time",
                value = typing.modifierSettleMs,
                range = 0..50,
                valueLabel = { "$it ms" },
                helper = "Pause after pressing Shift/Ctrl/Alt so the host registers it " +
                    "before the key it applies to.",
                onValueChange = { v -> viewModel.updateTyping { it.copy(modifierSettleMs = v) } },
                onReset = { viewModel.updateTyping { it.copy(modifierSettleMs = 5) } },
            )
            SettingsSlider(
                title = "Extra delay after Enter",
                value = typing.newlineExtraDelayMs,
                range = 0..500,
                valueLabel = { "$it ms" },
                helper = "Hosts often do visible work on Enter and drop input during it.",
                onValueChange = { v -> viewModel.updateTyping { it.copy(newlineExtraDelayMs = v) } },
                onReset = { viewModel.updateTyping { it.copy(newlineExtraDelayMs = 40) } },
            )
            SettingsSlider(
                title = "Extra delay before a repeated key",
                value = typing.repeatedKeyExtraDelayMs,
                range = 0..300,
                valueLabel = { "$it ms" },
                helper = "Pressing the same key twice in a row needs a clear gap, or the " +
                    "device treats the second press as key-repeat and drops it — \"ssss\" " +
                    "arrives as \"ss\". Raise this if repeated letters go missing.",
                onValueChange = { v ->
                    viewModel.updateTyping { it.copy(repeatedKeyExtraDelayMs = v) }
                },
                onReset = { viewModel.updateTyping { it.copy(repeatedKeyExtraDelayMs = 30) } },
            )
            SettingsSlider(
                title = "Extra delay after dead key",
                value = typing.deadKeyExtraDelayMs,
                range = 0..200,
                valueLabel = { "$it ms" },
                helper = "Time for the host to compose accented characters.",
                onValueChange = { v -> viewModel.updateTyping { it.copy(deadKeyExtraDelayMs = v) } },
                onReset = { viewModel.updateTyping { it.copy(deadKeyExtraDelayMs = 25) } },
            )
            SettingsSwitch(
                title = "Humanize",
                subtitle = "Vary the delays slightly, for hosts that reject perfect timing",
                checked = typing.humanize,
                onCheckedChange = { v -> viewModel.updateTypingMeta { it.copy(humanize = v) } },
            )
        }

        SettingsSection("Layout") {
            SettingsChoiceChips(
                title = "Default keyboard layout",
                options = BuiltInLayouts.ALL.map { it.id },
                selected = typing.defaultLayoutId,
                label = { id -> BuiltInLayouts.byId(id)?.name ?: id },
                onSelect = { id -> viewModel.updateTypingMeta { it.copy(defaultLayoutId = id) } },
                helper = "This must match the layout the *host* is set to, not this phone. " +
                    "Per-device settings override it.",
            )
        }

        SettingsSection("Characters this layout cannot type") {
            SettingsChoiceChips(
                title = "What to do",
                options = UnmappableAction.entries,
                selected = typing.unmappableAction,
                label = {
                    when (it) {
                        UnmappableAction.SKIP -> "Skip"
                        UnmappableAction.SUBSTITUTE -> "Substitute"
                        UnmappableAction.UNICODE_ESCAPE -> "Unicode escape"
                    }
                },
                onSelect = { v -> viewModel.updateTypingMeta { it.copy(unmappableAction = v) } },
                helper = "Emoji and other characters outside the layout. Unicode escape works " +
                    "on Linux and Windows; macOS has no equivalent, so it skips there.",
            )
        }

        SettingsSection("Safety") {
            SettingsSlider(
                title = "Confirm sends longer than",
                value = typing.confirmSendOverChars,
                range = 0..5_000,
                valueLabel = { if (it == 0) "Never" else "$it chars" },
                onValueChange = { v -> viewModel.updateTypingMeta { it.copy(confirmSendOverChars = v) } },
                onReset = { viewModel.updateTypingMeta { it.copy(confirmSendOverChars = 1_000) } },
            )
        }
    }
}

/**
 * Turns the delay numbers into something concrete.
 *
 * "12 ms" means nothing on its own; "about 61 chars/sec, 500 chars in 8 s" is
 * the thing the user is actually choosing between.
 */
@Composable
private fun ThroughputReadout(typing: TypingSettings) {
    val profile = SettingsResolver.resolveProfile(typing, device = null)
    val sample = LayoutMapper(BuiltInLayouts.DEFAULT).map("a".repeat(500)).strokes
    val millis = ReportScheduler.estimateDurationMs(sample, profile)
    val perSecond = profile.estimatedCharsPerSecond

    SettingsNote(
        "About ${perSecond.roundToInt()} characters/second · " +
            "500 characters in ${"%.1f".format(millis / 1000.0)} s",
    )
}

/** SPEC 7.3.2 — batching and retraction safety for live mode. */
@Composable
fun LiveSettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val live = settings.live

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsNote(
            "Live mode puts everything you type into the buffer first and sends it once it " +
                "settles. That is what stops autocorrect and swipe candidates reaching the " +
                "connected device.",
        )

        SettingsSection("Preset") {
            SettingsChoiceChips(
                title = "Behaviour",
                options = LiveSettings.PRESETS.map { it.presetId } + LiveSettings.CUSTOM,
                selected = live.presetId,
                label = { it.replaceFirstChar(Char::uppercase) },
                onSelect = { id -> if (id != LiveSettings.CUSTOM) viewModel.applyLivePreset(id) },
            )
        }

        SettingsSection("When to send") {
            SettingsSlider(
                title = "Settle delay",
                value = live.settleDelayMs,
                range = 0..2_000,
                valueLabel = { "$it ms" },
                helper = "How long you must pause before pending text is sent. Lower keeps the " +
                    "device closer to you but catches words mid-autocorrect.",
                onValueChange = { v -> viewModel.updateLive { it.copy(settleDelayMs = v) } },
                onReset = { viewModel.updateLive { it.copy(settleDelayMs = 400) } },
            )
            SettingsSwitch(
                title = "Send at every space",
                subtitle = "Flush immediately at word boundaries",
                checked = live.flushOnSpace,
                onCheckedChange = { v -> viewModel.updateLive { it.copy(flushOnSpace = v) } },
            )
            SettingsSwitch(
                title = "Send on Enter",
                checked = live.flushOnEnter,
                onCheckedChange = { v -> viewModel.updateLive { it.copy(flushOnEnter = v) } },
            )
            SettingsSlider(
                title = "Send once this much is waiting",
                value = live.pendingFlushThreshold ?: 0,
                range = 0..1_000,
                valueLabel = { if (it < 20) "Off" else "$it chars" },
                helper = "Stops long unbroken input piling up.",
                onValueChange = { v ->
                    viewModel.updateLive {
                        it.copy(pendingFlushThreshold = if (v < 20) null else v)
                    }
                },
                onReset = { viewModel.updateLive { it.copy(pendingFlushThreshold = 120) } },
            )
        }

        SettingsSection("Corrections") {
            SettingsSlider(
                title = "Retraction limit",
                value = live.retractionCap ?: 500,
                range = 0..500,
                valueLabel = { if (it >= 500) "Unlimited" else "$it chars" },
                helper = "The most backspaces sent to the connected device without asking " +
                    "first. Lower limits how much of its text could be wrongly deleted if " +
                    "its cursor has moved.",
                onValueChange = { v ->
                    viewModel.updateLive { it.copy(retractionCap = if (v >= 500) null else v) }
                },
                onReset = { viewModel.updateLive { it.copy(retractionCap = 64) } },
            )
            SettingsChoiceChips(
                title = "When the limit is exceeded",
                options = OverCapAction.entries,
                selected = live.overCapAction,
                label = {
                    when (it) {
                        OverCapAction.ASK -> "Ask"
                        OverCapAction.ALWAYS_RETYPE -> "Always retype"
                        OverCapAction.ALWAYS_SKIP_AND_RESYNC -> "Always skip"
                    }
                },
                onSelect = { v -> viewModel.updateLive { it.copy(overCapAction = v) } },
            )
            SettingsSwitch(
                title = "Warn on mid-text edits",
                checked = live.warnOnMidTextEdit,
                onCheckedChange = { v -> viewModel.updateLive { it.copy(warnOnMidTextEdit = v) } },
            )
        }

        SettingsSection("Connection drops") {
            SettingsChoiceChips(
                title = "On reconnect",
                options = ReconnectPolicy.entries,
                selected = live.reconnectPolicy,
                label = {
                    when (it) {
                        ReconnectPolicy.ASK -> "Ask"
                        ReconnectPolicy.RESUME -> "Send what queued"
                        ReconnectPolicy.RESET_WATERMARK -> "Discard queue"
                    }
                },
                onSelect = { v -> viewModel.updateLive { it.copy(reconnectPolicy = v) } },
                helper = "Text typed while disconnected is held. This decides what happens " +
                    "to it when the device comes back.",
            )
            SettingsSwitch(
                title = "Keep live typing in the background",
                checked = live.keepAliveInBackground,
                onCheckedChange = { v ->
                    viewModel.updateLive { it.copy(keepAliveInBackground = v) }
                },
            )
        }

        SettingsSection("Reset") {
            SettingsLink(
                title = "Reset live typing settings",
                subtitle = "Back to Balanced defaults",
                onClick = viewModel::resetLive,
            )
        }
    }
}
