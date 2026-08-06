package dev.cwtf.hidandseek.data.agent

import kotlinx.serialization.Serializable

/**
 * How much freedom the model has to put keystrokes into the connected machine.
 *
 * [ASK] is the default and is restored on every launch. [AUTO] is an in-session
 * choice only: it never persists, never survives a disconnect, and cannot be
 * set as the startup mode. [OFF] withholds the tools entirely, so the model is
 * not even told it can type.
 */
@Serializable
enum class AgentMode { OFF, ASK, AUTO }

@Serializable
data class AgentSettings(
    /** Most characters one tool call may type. */
    val charCap: Int = 2_000,
    val maxCallsPerTurn: Int = 3,
    val maxCallsPerMinute: Int = 10,
    /** Auto reverts to Ask after this long. */
    val autoExpiryMinutes: Int = 15,
    /** Patterns that always require confirmation, even in Auto. */
    val blocklist: List<String> = DEFAULT_BLOCKLIST,
    /** Tell the model the device nickname, layout, and OS. */
    val injectHostContext: Boolean = false,
    /** Confirm before the user's own "Type to host" actions, too. */
    val confirmUserInitiatedSends: Boolean = true,
    /** Trim history to this many characters before sending. */
    val historyCharBudget: Int = 24_000,
) {
    companion object {
        /**
         * Patterns worth a second look before a model types them somewhere.
         *
         * Not a security boundary — anyone can phrase a destructive command
         * differently. It is a speed bump on the cases that are both common and
         * unrecoverable.
         */
        val DEFAULT_BLOCKLIST = listOf(
            """rm\s+-[rRf]""",
            """mkfs(\.|\s)""",
            """dd\s+if=""",
            """:\(\)\s*\{.*\|.*&.*\}""",
            """\bformat\s+[a-zA-Z]:""",
            """\bdel\s+/[sSqQfF]""",
            """>\s*/dev/sd[a-z]""",
            """\bsudo\b""",
            """shutdown|reboot\s+now""",
            """(?i)(api[_-]?key|password|secret|token)\s*[=:]""",
        )
    }
}

/** A keystroke request from the model, before any decision has been made. */
data class TypeToHostRequest(
    val callId: String,
    val text: String,
    val pressEnter: Boolean = false,
    val delayMsOverride: Int? = null,
)

/** A key-combination request, e.g. `ctrl+alt+t`. */
data class PressKeysRequest(
    val callId: String,
    val combo: String,
)

sealed interface AgentDecision {
    /** Execute without asking. Only reachable in [AgentMode.AUTO]. */
    data object Allow : AgentDecision

    /** Show the approval card. Carries why, so the card can explain itself. */
    data class RequireApproval(val reason: ApprovalReason) : AgentDecision

    /** Refuse outright; the model is told why so it can adapt. */
    data class Deny(val reason: String) : AgentDecision
}

enum class ApprovalReason {
    /** Ask mode — every request is confirmed. */
    ASK_MODE,

    /** Auto, but the text matched the blocklist. */
    BLOCKLISTED,
}

/** Rate-limiting state, owned by the caller so the engine stays pure. */
data class AgentRateState(
    val callsThisTurn: Int = 0,
    val recentCallTimestamps: List<Long> = emptyList(),
    /** When Auto was switched on; null when not in Auto. */
    val autoSinceEpochMs: Long? = null,
) {
    fun recordCall(nowEpochMs: Long) = copy(
        callsThisTurn = callsThisTurn + 1,
        recentCallTimestamps = (recentCallTimestamps + nowEpochMs).takeLast(120),
    )

    fun startTurn() = copy(callsThisTurn = 0)

    fun callsInLastMinute(nowEpochMs: Long) =
        recentCallTimestamps.count { nowEpochMs - it < 60_000 }
}

@Serializable
data class AgentAuditEntry(
    val id: String,
    val deviceAddress: String?,
    val mode: AgentMode,
    /** First 80 characters, for the log. Redacted content never lands here. */
    val preview: String,
    val charCount: Int,
    val approved: Boolean,
    val result: String,
    val atEpochMs: Long,
)
