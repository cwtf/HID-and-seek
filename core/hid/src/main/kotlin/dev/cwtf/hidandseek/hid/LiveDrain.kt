package dev.cwtf.hidandseek.hid

/** What to do when a retraction would exceed the configured cap. */
enum class OverCapAction { ASK, ALWAYS_RETYPE, ALWAYS_SKIP_AND_RESYNC }

/** What to do with queued text when a dropped connection comes back. */
enum class ReconnectPolicy { ASK, RESUME, RESET_WATERMARK }

data class LiveTypingConfig(
    /** Quiet period with no edits before pending text is flushed. */
    val settleDelayMs: Int = 400,
    val flushOnSpace: Boolean = true,
    val flushOnEnter: Boolean = true,
    /** Force a flush once this much text is pending. Null disables. */
    val pendingFlushThreshold: Int? = 120,
    /** Most backspaces sent without asking. Null means unlimited. */
    val retractionCap: Int? = 64,
    val overCapAction: OverCapAction = OverCapAction.ASK,
) {
    init {
        require(settleDelayMs in 0..2_000) { "settleDelayMs out of range: $settleDelayMs" }
        require(pendingFlushThreshold == null || pendingFlushThreshold in 20..1_000) {
            "pendingFlushThreshold out of range: $pendingFlushThreshold"
        }
        require(retractionCap == null || retractionCap in 0..500) {
            "retractionCap out of range: $retractionCap"
        }
    }
}

/** What the host needs in order to match the buffer. */
sealed interface DrainPlan {
    data object Idle : DrainPlan

    /** Append [text]. The common case. */
    data class Type(val text: String) : DrainPlan

    /**
     * Delete [backspaces] characters from the host, then type [thenType].
     *
     * Produced whenever already-sent text changed: a backspace, an autocorrect
     * rewrite of a finished word, or an edit in the middle of the buffer. All
     * three are the same operation.
     */
    data class Retract(val backspaces: Int, val thenType: String) : DrainPlan

    /**
     * Give up on matching the host and treat [newSentText] as its contents.
     *
     * Sends nothing. Leaves the host out of sync with the buffer, which is why
     * it is only ever reached through an explicit user choice.
     */
    data class Resync(val newSentText: String) : DrainPlan
}

sealed interface DrainDecision {
    data object Idle : DrainDecision

    /** Hold [plan] until [afterMs] of quiet, unless a flush trigger arrives. */
    data class Defer(val afterMs: Int, val plan: DrainPlan) : DrainDecision

    data class Execute(val plan: DrainPlan) : DrainDecision

    /** Retraction exceeds the cap — the user has to choose. */
    data class AskOverCap(val plan: DrainPlan.Retract, val cap: Int) : DrainDecision
}

enum class EditTrigger { TYPING, SPACE, ENTER, PASTE, MANUAL_FLUSH }

/**
 * Buffer-first live typing.
 *
 * The staging buffer is the single source of truth and the host is a replica
 * that lags it. Nothing is intercepted at the keyboard: predictive text, swipe
 * input, autocorrect, and CJK composition all mutate the buffer first, and only
 * *settled* text is transmitted. That is what keeps half-finished autocorrect
 * candidates from reaching the host.
 *
 * Two rules do all the work:
 *
 *  1. Never transmit inside an active composing region.
 *  2. On any divergence, find the longest common prefix between what the host
 *     holds and what it should hold, backspace the difference, and retype.
 *
 * Rule 2 collapses append, backspace, autocorrect rewrite, and mid-buffer edit
 * into one operation, so there is one implementation and one set of tests
 * rather than four special cases.
 *
 * All state transitions are synchronous and side-effect free; the caller owns
 * the timer and the transport. This class never sends anything itself.
 */
class LiveDrain(
    config: LiveTypingConfig = LiveTypingConfig(),
) {

    /** Live-editable: settings changes apply without restarting live mode. */
    var config: LiveTypingConfig = config

    /**
     * Exactly what the host has received.
     *
     * Not an index into the buffer — an index would be meaningless the moment
     * the buffer changed underneath it. Holding the actual text is what makes
     * divergence detection a prefix comparison.
     */
    var sentText: String = ""
        private set

    var isConnected: Boolean = true
        private set

    /** Characters waiting to go to the host, given the last known buffer. */
    fun pendingCount(buffer: String, compositionStart: Int? = null): Int =
        when (val plan = plan(buffer, compositionStart)) {
            is DrainPlan.Type -> plan.text.codePointCount()
            is DrainPlan.Retract -> plan.thenType.codePointCount()
            else -> 0
        }

    /**
     * Works out what the host needs, without deciding when to do it.
     *
     * [compositionStart] is where the IME's active composing region begins;
     * null when there is none. Text from there onward is excluded because it is
     * still being edited by the keyboard.
     */
    fun plan(buffer: String, compositionStart: Int? = null): DrainPlan {
        val cut = (compositionStart ?: buffer.length).coerceIn(0, buffer.length)
        val target = buffer.substring(0, cut)

        val common = commonPrefixLength(sentText, target)
        val backspaces = sentText.substring(common).codePointCount()
        val toType = target.substring(common)

        return when {
            backspaces == 0 && toType.isEmpty() -> DrainPlan.Idle
            backspaces == 0 -> DrainPlan.Type(toType)
            else -> DrainPlan.Retract(backspaces, toType)
        }
    }

    /** Called on every buffer change. */
    fun onEdit(
        buffer: String,
        compositionStart: Int? = null,
        trigger: EditTrigger = EditTrigger.TYPING,
    ): DrainDecision {
        if (!isConnected) return DrainDecision.Idle

        val plan = plan(buffer, compositionStart)
        if (plan is DrainPlan.Idle) return DrainDecision.Idle

        return if (shouldFlushImmediately(plan, trigger)) {
            decide(plan)
        } else {
            DrainDecision.Defer(config.settleDelayMs, plan)
        }
    }

    /** Called when the settle timer expires with no further edits. */
    fun onSettleElapsed(buffer: String, compositionStart: Int? = null): DrainDecision {
        if (!isConnected) return DrainDecision.Idle
        val plan = plan(buffer, compositionStart)
        return if (plan is DrainPlan.Idle) DrainDecision.Idle else decide(plan)
    }

    /** "Catch up now" — flush regardless of the settle delay. */
    fun onManualFlush(buffer: String, compositionStart: Int? = null): DrainDecision =
        onEdit(buffer, compositionStart, EditTrigger.MANUAL_FLUSH)

    private fun shouldFlushImmediately(plan: DrainPlan, trigger: EditTrigger): Boolean {
        if (trigger == EditTrigger.MANUAL_FLUSH) return true
        if (trigger == EditTrigger.ENTER && config.flushOnEnter) return true
        if (trigger == EditTrigger.SPACE && config.flushOnSpace) return true

        val threshold = config.pendingFlushThreshold ?: return false
        val pending = when (plan) {
            is DrainPlan.Type -> plan.text.codePointCount()
            is DrainPlan.Retract -> plan.thenType.codePointCount()
            else -> 0
        }
        return pending >= threshold
    }

    private fun decide(plan: DrainPlan): DrainDecision {
        if (plan !is DrainPlan.Retract) return DrainDecision.Execute(plan)

        val cap = config.retractionCap ?: return DrainDecision.Execute(plan)
        if (plan.backspaces <= cap) return DrainDecision.Execute(plan)

        return when (config.overCapAction) {
            OverCapAction.ASK -> DrainDecision.AskOverCap(plan, cap)
            OverCapAction.ALWAYS_RETYPE -> DrainDecision.Execute(plan)
            OverCapAction.ALWAYS_SKIP_AND_RESYNC ->
                DrainDecision.Execute(DrainPlan.Resync(sentText + plan.thenType))
        }
    }

    // --- results ------------------------------------------------------------
    //
    // Reported incrementally rather than as one "it worked" call, so a send
    // that dies halfway leaves `sentText` describing what the host actually
    // holds rather than what was intended.

    fun onTextTyped(text: String) {
        sentText += text
    }

    fun onBackspacesApplied(count: Int) {
        sentText = sentText.dropLastCodePoints(count)
    }

    /** Applies a whole plan that completed successfully. */
    fun onExecuted(plan: DrainPlan) {
        when (plan) {
            is DrainPlan.Type -> onTextTyped(plan.text)
            is DrainPlan.Retract -> {
                onBackspacesApplied(plan.backspaces)
                onTextTyped(plan.thenType)
            }

            is DrainPlan.Resync -> sentText = plan.newSentText
            DrainPlan.Idle -> Unit
        }
    }

    // --- connection ---------------------------------------------------------

    /**
     * Pauses draining. The buffer keeps accumulating; because [sentText] is
     * authoritative, whatever piled up is simply part of the next plan — there
     * is no separate queue to manage.
     */
    fun onDisconnected() {
        isConnected = false
    }

    fun onReconnected(
        policy: ReconnectPolicy,
        buffer: String,
        compositionStart: Int? = null,
    ): DrainDecision {
        isConnected = true
        return when (policy) {
            ReconnectPolicy.RESUME -> onManualFlush(buffer, compositionStart)
            ReconnectPolicy.RESET_WATERMARK -> {
                val cut = (compositionStart ?: buffer.length).coerceIn(0, buffer.length)
                sentText = buffer.substring(0, cut)
                DrainDecision.Idle
            }

            ReconnectPolicy.ASK -> DrainDecision.Idle
        }
    }

    // --- mode switching -----------------------------------------------------

    /** Entering live mode with the host assumed to already hold [text]. */
    fun assumeHostHolds(text: String) {
        sentText = text
    }

    /** Entering live mode with the host assumed empty — everything is pending. */
    fun reset() {
        sentText = ""
    }
}

// --- code-point-safe helpers ------------------------------------------------
//
// Counted in code points, not UTF-16 units: a host backspace removes one
// character, and splitting a surrogate pair would corrupt the comparison.

internal fun String.codePointCount(): Int = codePointCount(0, length)

internal fun String.dropLastCodePoints(count: Int): String {
    if (count <= 0) return this
    var index = length
    repeat(count) {
        if (index <= 0) return ""
        index = offsetByCodePoints(index, -1)
    }
    return substring(0, index)
}

/** Length in UTF-16 units of the common prefix, never splitting a code point. */
internal fun commonPrefixLength(a: String, b: String): Int {
    var i = 0
    while (i < a.length && i < b.length) {
        val cpA = a.codePointAt(i)
        if (cpA != b.codePointAt(i)) break
        i += Character.charCount(cpA)
    }
    return i
}
