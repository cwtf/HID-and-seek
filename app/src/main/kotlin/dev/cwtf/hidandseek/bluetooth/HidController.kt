package dev.cwtf.hidandseek.bluetooth

import android.content.Context
import dev.cwtf.hidandseek.data.ResolvedConfig
import dev.cwtf.hidandseek.hid.BuiltInLayouts
import dev.cwtf.hidandseek.hid.DrainPlan
import dev.cwtf.hidandseek.hid.KeyLayout
import dev.cwtf.hidandseek.hid.KeyStroke
import dev.cwtf.hidandseek.hid.LayoutMapper
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

    /** Panic key: drop every held key immediately. */
    suspend fun releaseAllKeys() = transport.releaseAllKeys()
}
