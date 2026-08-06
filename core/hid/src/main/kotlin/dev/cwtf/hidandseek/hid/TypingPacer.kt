package dev.cwtf.hidandseek.hid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** One report and the pause that precedes it. */
data class TimedReport(
    val report: KeyboardReport,
    val delayBeforeMs: Int,
    /** Index into the source stroke list, for progress reporting. */
    val strokeIndex: Int,
    /**
     * True on the last report of a stroke. Progress counts these rather than
     * all-zero reports, because a stroke holding modifiers across a run ends
     * with a modifier-only report instead of a release.
     */
    val isStrokeEnd: Boolean = false,
)

/**
 * Expands strokes into the exact report sequence and timing to transmit.
 *
 * Pure, so all the timing rules are testable without a clock, a transport, or
 * a coroutine. [TypingPacer] is the thin part that actually waits and sends.
 */
object ReportScheduler {

    fun schedule(
        strokes: List<KeyStroke>,
        profile: TypingProfile,
        jitter: Jitter = Jitter.NONE,
    ): List<TimedReport> {
        val out = mutableListOf<TimedReport>()
        val effectiveJitter = if (profile.humanize) jitter else Jitter.NONE

        strokes.forEachIndexed { index, stroke ->
            val previous = strokes.getOrNull(index - 1)
            val gap = if (previous == null) {
                0
            } else {
                effectiveJitter.apply(profile.interKeyDelayMs) +
                    extraAfter(previous, profile) +
                    // Pressing the same key again needs a clear gap, or the
                    // host treats the second press as key-repeat noise and
                    // drops it — turning "ssss" into "ss".
                    if (previous.usage == stroke.usage) profile.repeatedKeyExtraDelayMs else 0
            }

            // Assert modifiers on their own first so the host sees them before
            // the key they apply to. Skipped when there are none, or when the
            // profile sets no settle time.
            val needsSettle = !stroke.modifiers.isEmpty && profile.modifierSettleMs > 0
            if (needsSettle) {
                out += TimedReport(KeyboardReport.of(stroke.modifiers), gap, index)
                out += TimedReport(stroke.downReport(), profile.modifierSettleMs, index)
            } else {
                out += TimedReport(stroke.downReport(), gap, index)
            }

            out += TimedReport(stroke.upReport(), profile.keyHoldMs, index, isStrokeEnd = true)
        }
        return out
    }

    private fun extraAfter(stroke: KeyStroke, profile: TypingProfile): Int = when (stroke.kind) {
        KeyStroke.Kind.NEWLINE -> profile.newlineExtraDelayMs
        KeyStroke.Kind.DEAD_KEY -> profile.deadKeyExtraDelayMs
        else -> 0
    }

    /** Total wall time the schedule should take, before jitter. */
    fun estimateDurationMs(strokes: List<KeyStroke>, profile: TypingProfile): Long =
        schedule(strokes, profile).sumOf { it.delayBeforeMs.toLong() }
}

/** Monotonic time source, injectable so pacing can be tested without waiting. */
interface PacerClock {
    fun elapsedNanos(): Long
    suspend fun sleepUntil(targetNanos: Long)
}

object SystemPacerClock : PacerClock {
    override fun elapsedNanos(): Long = System.nanoTime()

    override suspend fun sleepUntil(targetNanos: Long) {
        val remaining = targetNanos - System.nanoTime()
        if (remaining > 0) delay(remaining / 1_000_000L)
    }
}

sealed interface SendOutcome {
    val strokesSent: Int

    data class Completed(override val strokesSent: Int) : SendOutcome
    data class Failed(override val strokesSent: Int, val cause: Throwable) : SendOutcome
    data class Cancelled(override val strokesSent: Int) : SendOutcome
}

/**
 * Transmits a schedule to a host at the requested pace.
 *
 * Timing is corrected against a monotonic clock rather than accumulated from
 * `delay()` calls, so a slow Bluetooth write does not compound into progressive
 * drift over a long send. If the transport falls behind the schedule the pacer
 * stops waiting and catches up rather than stretching the whole send.
 *
 * On failure or cancellation every key is released, because a modifier left
 * asserted leaves the host broken in a way that survives disconnecting.
 */
class TypingPacer(
    private val transport: HidTransport,
    private val clock: PacerClock = SystemPacerClock,
) {

    suspend fun send(
        schedule: List<TimedReport>,
        onProgress: (strokesSent: Int) -> Unit = {},
    ): SendOutcome {
        if (schedule.isEmpty()) return SendOutcome.Completed(0)

        var lastStrokeIndex = -1
        var target = clock.elapsedNanos()

        try {
            for (timed in schedule) {
                currentCoroutineContext().ensureActive()

                target += timed.delayBeforeMs * 1_000_000L
                val now = clock.elapsedNanos()
                if (target > now) {
                    clock.sleepUntil(target)
                } else {
                    // Already behind: don't try to claw back time we can't have.
                    target = now
                }

                val result = transport.sendKeyboardReport(timed.report)
                result.exceptionOrNull()?.let { cause ->
                    transport.releaseAllKeys()
                    return SendOutcome.Failed(lastStrokeIndex + 1, cause)
                }

                if (timed.isStrokeEnd) {
                    lastStrokeIndex = timed.strokeIndex
                    onProgress(lastStrokeIndex + 1)
                }
            }
        } catch (e: CancellationException) {
            transport.releaseAllKeys()
            throw e
        }

        return SendOutcome.Completed(lastStrokeIndex + 1)
    }
}
