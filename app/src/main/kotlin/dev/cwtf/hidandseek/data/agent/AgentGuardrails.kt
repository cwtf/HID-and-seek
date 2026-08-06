package dev.cwtf.hidandseek.data.agent

/**
 * Decides whether a model-requested keystroke send may proceed.
 *
 * Pure and side-effect free so every rule is testable without a device, a
 * network, or a model. This is the component standing between a language model
 * and someone's actual keyboard, so the rules here are worth being able to
 * prove rather than eyeball.
 *
 * Order matters: hard denials come first, then the checks that can be overridden
 * by a human, then the default. Nothing reaches [AgentDecision.Allow] except
 * through Auto mode with every check passed.
 */
object AgentGuardrails {

    fun evaluate(
        request: TypeToHostRequest,
        mode: AgentMode,
        settings: AgentSettings,
        state: AgentRateState,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AgentDecision {
        if (mode == AgentMode.OFF) {
            return AgentDecision.Deny("Agent typing is switched off")
        }

        if (request.text.isEmpty()) {
            return AgentDecision.Deny("Nothing to type")
        }

        // Hard caps first — these hold in every mode, including Ask, so a
        // user cannot be talked into approving a 50,000-character paste.
        if (request.text.length > settings.charCap) {
            return AgentDecision.Deny(
                "Too long: ${request.text.length} characters, limit is ${settings.charCap}",
            )
        }

        if (state.callsThisTurn >= settings.maxCallsPerTurn) {
            return AgentDecision.Deny(
                "Too many typing requests in one reply (limit ${settings.maxCallsPerTurn})",
            )
        }

        if (state.callsInLastMinute(nowEpochMs) >= settings.maxCallsPerMinute) {
            return AgentDecision.Deny(
                "Too many typing requests per minute (limit ${settings.maxCallsPerMinute})",
            )
        }

        if (mode == AgentMode.ASK) {
            return AgentDecision.RequireApproval(ApprovalReason.ASK_MODE)
        }

        // Auto mode from here. Expiry is checked by the caller when it decides
        // the effective mode, so reaching this point means Auto is still live.
        if (matchesBlocklist(request.text, settings.blocklist)) {
            return AgentDecision.RequireApproval(ApprovalReason.BLOCKLISTED)
        }

        return AgentDecision.Allow
    }

    /**
     * Whether [text] matches any blocklist pattern.
     *
     * An invalid user-supplied pattern is treated as matching nothing rather
     * than crashing — a typo in the blocklist editor should not take the
     * feature down, and the other patterns still apply.
     */
    fun matchesBlocklist(text: String, patterns: List<String>): Boolean =
        patterns.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }

    /** Which blocklist patterns matched, for explaining the approval card. */
    fun matchingPatterns(text: String, patterns: List<String>): List<String> =
        patterns.filter { pattern ->
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }

    /**
     * The mode actually in force, after expiry.
     *
     * Auto is deliberately temporary: it lapses back to Ask after the configured
     * window and on any disconnect, so leaving it on by accident has a bounded
     * cost.
     */
    fun effectiveMode(
        requested: AgentMode,
        state: AgentRateState,
        settings: AgentSettings,
        connected: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AgentMode {
        if (requested != AgentMode.AUTO) return requested
        if (!connected) return AgentMode.ASK

        val since = state.autoSinceEpochMs ?: return AgentMode.ASK
        val elapsedMinutes = (nowEpochMs - since) / 60_000
        return if (elapsedMinutes >= settings.autoExpiryMinutes) AgentMode.ASK else AgentMode.AUTO
    }
}
