package dev.cwtf.hidandseek.bluetooth

import android.content.Context
import dev.cwtf.hidandseek.data.ResolvedConfig
import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.ConsumerReport
import dev.cwtf.hidandseek.hid.Modifiers
import dev.cwtf.hidandseek.hid.DrainPlan
import dev.cwtf.hidandseek.hid.KeyCombo
import dev.cwtf.hidandseek.hid.KeyLayout
import dev.cwtf.hidandseek.hid.KeyStroke
import dev.cwtf.hidandseek.hid.LayoutMapper
import dev.cwtf.hidandseek.hid.LedState
import dev.cwtf.hidandseek.hid.UnmappableChar
import dev.cwtf.hidandseek.hid.UnmappableHandling
import dev.cwtf.hidandseek.hid.LiveDrain
import dev.cwtf.hidandseek.hid.ReportScheduler
import dev.cwtf.hidandseek.hid.SendOutcome
import dev.cwtf.hidandseek.hid.TypingPacer
import dev.cwtf.hidandseek.hid.TypingProfile
import dev.cwtf.hidandseek.hid.UnmappablePolicy
import dev.cwtf.hidandseek.hid.releaseAllKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progress of an in-flight send, for the UI. */
data class SendProgress(
    val charsSent: Int,
    val charsTotal: Int,
) {
    val fraction: Float get() = if (charsTotal == 0) 0f else charsSent.toFloat() / charsTotal
}

/** What a pending send will do to the host. */
data class SendPreview(
    val characters: Int,
    val strokes: Int,
    val unmappable: List<UnmappableChar>,
    val estimatedMs: Long,
    val warnings: List<String>,
) {
    val skipped: Int get() = unmappable.count { it.handling == UnmappableHandling.SKIPPED }
}

/** Outcome of typing a block of text, in the caller's terms rather than strokes. */
sealed interface TypeResult {
    data class Delivered(val chars: Int, val skipped: Int) : TypeResult

    /** The link died partway. [charsDelivered] is what the host really received. */
    data class Partial(val charsDelivered: Int, val cause: Throwable) : TypeResult

    data class Rejected(val cause: Throwable) : TypeResult
}

/**
 * Owns the typing pipeline: transport, layout, profile, and pacing.
 *
 * Sends are serialised through a mutex so two screens (staged send and the live
 * drain, say) can never interleave reports into the same host.
 */
class HidController(context: Context) {

    val transport = BluetoothHidTransport(context.applicationContext)

    private val pacer = TypingPacer(transport)
    private val sendLock = Mutex()

    var layout: KeyLayout = BuiltInLayouts.DEFAULT
        private set
    var profile: TypingProfile = TypingProfile.DEFAULT
        private set
    var unmappablePolicy: UnmappablePolicy = UnmappablePolicy.Skip
        private set

    /** Live-typing state. The buffer lives in the UI; this tracks the host. */
    val drain = LiveDrain()

    /** Address of the host in use, so per-device overrides can be resolved. */
    val activeAddress = MutableStateFlow<String?>(null)

    /**
     * Applies resolved settings.
     *
     * Takes effect on the next send rather than restarting anything, so
     * changing a slider mid-session does not interrupt live typing.
     */
    fun applyConfig(resolved: ResolvedConfig) {
        layout = resolved.layout
        profile = resolved.profile
        unmappablePolicy = resolved.unmappablePolicy
        drain.config = resolved.live
    }

    private val _progress = MutableStateFlow<SendProgress?>(null)
    val progress = _progress.asStateFlow()

    private fun mapper() = LayoutMapper(
        layout = layout,
        policy = unmappablePolicy,
        hostCapsLock = transport.hostLedState.value.capsLock,
    )

    /** Types [text] on the host. Cancelling the calling coroutine stops it. */
    suspend fun typeText(text: String, appendEnter: Boolean = false): TypeResult =
        sendLock.withLock {
            val source = if (appendEnter) text + "\n" else text
            val mapping = mapper().map(source)
            if (mapping.strokes.isEmpty()) {
                return@withLock TypeResult.Delivered(0, mapping.skipped.size)
            }

            val schedule = ReportScheduler.schedule(mapping.strokes, profile)
            _progress.value = SendProgress(0, source.length)

            try {
                val outcome = pacer.send(schedule) { strokesSent ->
                    _progress.value = SendProgress(
                        mapping.charsDeliveredAfter(strokesSent),
                        source.length,
                    )
                }
                when (outcome) {
                    is SendOutcome.Completed ->
                        TypeResult.Delivered(source.length, mapping.skipped.size)

                    is SendOutcome.Failed -> TypeResult.Partial(
                        mapping.charsDeliveredAfter(outcome.strokesSent),
                        outcome.cause,
                    )

                    is SendOutcome.Cancelled -> TypeResult.Partial(
                        mapping.charsDeliveredAfter(outcome.strokesSent),
                        kotlinx.coroutines.CancellationException("Send cancelled"),
                    )
                }
            } finally {
                _progress.value = null
            }
        }

    /** Sends [count] backspaces. Used by live-mode retraction. */
    private suspend fun sendBackspaces(count: Int): Int = sendLock.withLock {
        if (count <= 0) return@withLock 0
        val strokes = List(count) { KeyStroke.BACKSPACE }
        val outcome = pacer.send(ReportScheduler.schedule(strokes, profile))
        outcome.strokesSent
    }

    /**
     * Applies a drain plan and records the result against the drain.
     *
     * Each step is reported as it lands rather than all at once, so a failure
     * halfway leaves the drain's view of the host accurate instead of
     * optimistic — which matters, because the next plan is computed from it.
     */
    suspend fun execute(plan: DrainPlan): TypeResult = when (plan) {
        is DrainPlan.Idle -> TypeResult.Delivered(0, 0)

        is DrainPlan.Resync -> {
            drain.onExecuted(plan)
            TypeResult.Delivered(0, 0)
        }

        is DrainPlan.Type -> typeText(plan.text).also { result ->
            recordTyped(plan.text, result)
        }

        is DrainPlan.Retract -> {
            val applied = sendBackspaces(plan.backspaces)
            drain.onBackspacesApplied(applied)
            if (applied < plan.backspaces) {
                TypeResult.Partial(0, ReportRejected)
            } else {
                typeText(plan.thenType).also { result -> recordTyped(plan.thenType, result) }
            }
        }
    }

    private fun recordTyped(text: String, result: TypeResult) {
        when (result) {
            is TypeResult.Delivered -> drain.onTextTyped(text)
            is TypeResult.Partial -> drain.onTextTyped(text.take(result.charsDelivered))
            is TypeResult.Rejected -> Unit
        }
    }

    /** Presses a key combination such as `ctrl+alt+t`. */
    suspend fun pressCombo(combo: String): TypeResult {
        val stroke = KeyCombo.parse(combo, layout)
            ?: return TypeResult.Rejected(IllegalArgumentException("Unrecognised key combo: $combo"))

        return sendLock.withLock {
            val outcome = pacer.send(ReportScheduler.schedule(listOf(stroke), profile))
            when (outcome) {
                is SendOutcome.Completed -> TypeResult.Delivered(1, 0)
                is SendOutcome.Failed -> TypeResult.Rejected(outcome.cause)
                is SendOutcome.Cancelled -> TypeResult.Partial(0, ReportRejected)
            }
        }
    }

    /**
     * Presses one key with the given modifiers held.
     *
     * Used by the macro sheet, where the key is chosen directly rather than
     * derived from text.
     */
    suspend fun pressKey(usage: Int, modifiers: Modifiers = Modifiers.NONE): TypeResult =
        sendLock.withLock {
            val stroke = KeyStroke(modifiers, usage, KeyStroke.Kind.CONTROL)
            when (val outcome = pacer.send(ReportScheduler.schedule(listOf(stroke), profile))) {
                is SendOutcome.Completed -> TypeResult.Delivered(1, 0)
                is SendOutcome.Failed -> TypeResult.Rejected(outcome.cause)
                is SendOutcome.Cancelled -> TypeResult.Partial(0, ReportRejected)
            }
        }

    /** Sends a media or system key on the consumer collection. */
    suspend fun pressConsumerKey(usage: Int): TypeResult = sendLock.withLock {
        val down = transport.sendConsumerReport(ConsumerReport(usage))
        down.exceptionOrNull()?.let { return@withLock TypeResult.Rejected(it) }
        // Consumer reports are level-triggered too: without the zero report the
        // host sees the key as still held.
        transport.sendConsumerReport(ConsumerReport(0))
        TypeResult.Delivered(1, 0)
    }

    /**
     * What a send would do, without doing it.
     *
     * Shown before sending because a mistake here lands in someone else's
     * machine — the characters this layout cannot produce are worth knowing
     * about beforehand rather than discovering afterwards.
     */
    fun previewSend(text: String, appendEnter: Boolean): SendPreview {
        val source = if (appendEnter) text + "\n" else text
        val mapping = mapper().map(source)
        val warnings = buildList {
            if (source.lines().any { it != it.trimStart() && it.isNotBlank() }) {
                add("Leading indentation present — editors that auto-indent will mangle it")
            }
            if (transport.hostLedState.value == LedState.UNKNOWN) {
                add("Caps Lock state on the device is unknown")
            }
            if (source.length > 5_000) add("This is a long send (${source.length} characters)")
        }
        return SendPreview(
            characters = source.length,
            strokes = mapping.strokes.size,
            unmappable = mapping.unmappable,
            estimatedMs = ReportScheduler.estimateDurationMs(mapping.strokes, profile),
            warnings = warnings,
        )
    }

    /** Read-only host state, for the agent's `get_host_status` tool. */
    fun hostStatus(): String = buildString {
        append("connected=").append(transport.state.value.canSend)
        append(", layout=").append(layout.id)
        append(", typingProfile=").append(profile.id)
        val leds = transport.hostLedState.value
        append(", capsLock=").append(leds.capsLock)
        append(", numLock=").append(leds.numLock)
    }

    /** Panic key: drop every held key immediately. */
    suspend fun releaseAllKeys() = transport.releaseAllKeys()
}
