package dev.cwtf.hidandseek.hid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakePacerClock : PacerClock {
    var nanos = 0L
    val sleptForMs = mutableListOf<Long>()

    override fun elapsedNanos(): Long = nanos

    override suspend fun sleepUntil(targetNanos: Long) {
        sleptForMs += (targetNanos - nanos) / 1_000_000L
        nanos = targetNanos
    }

    fun advanceMs(ms: Long) {
        nanos += ms * 1_000_000L
    }
}

class ReportSchedulerTest {

    private val mapper = LayoutMapper(BuiltInLayouts.US_QWERTY)

    @Test
    fun `every key-down is followed by a key release`() {
        val strokes = mapper.map("Hello, World! 123").strokes
        val schedule = ReportScheduler.schedule(strokes, TypingProfile.NORMAL)

        val reports = schedule.map { it.report }
        reports.forEachIndexed { i, report ->
            if (report.keys.isNotEmpty()) {
                assertTrue(
                    i + 1 < reports.size && reports[i + 1].keys.isEmpty(),
                    "report $i pressed a key that was never released — the host would auto-repeat",
                )
            }
        }
    }

    @Test
    fun `schedule never exceeds one key at a time`() {
        val strokes = mapper.map("abcdefghij").strokes
        val schedule = ReportScheduler.schedule(strokes, TypingProfile.NORMAL)
        assertTrue(schedule.all { it.report.keys.size <= 1 })
    }

    @Test
    fun `modifiers are asserted before the key they modify`() {
        val strokes = mapper.map("A").strokes
        val schedule = ReportScheduler.schedule(strokes, TypingProfile.NORMAL)

        assertEquals(3, schedule.size, "modifier-only, key-down, release")
        assertTrue(schedule[0].report.keys.isEmpty())
        assertEquals(Modifiers.LEFT_SHIFT, schedule[0].report.modifiers)
        assertEquals(
            TypingProfile.NORMAL.modifierSettleMs,
            schedule[1].delayBeforeMs,
            "the settle delay sits between the modifier and the key",
        )
        assertEquals(Usage.letter('a'), schedule[1].report.keys.single())
    }

    @Test
    fun `unmodified keys skip the modifier report`() {
        val schedule = ReportScheduler.schedule(mapper.map("a").strokes, TypingProfile.NORMAL)
        assertEquals(2, schedule.size, "key-down and release only")
    }

    @Test
    fun `no settle report when the profile sets no settle time`() {
        val profile = TypingProfile.NORMAL.copy(modifierSettleMs = 0)
        val schedule = ReportScheduler.schedule(mapper.map("A").strokes, profile)
        assertEquals(2, schedule.size)
        assertEquals(Modifiers.LEFT_SHIFT, schedule[0].report.modifiers)
        assertEquals(Usage.letter('a'), schedule[0].report.keys.single())
    }

    @Test
    fun `newline gets the extra delay and dead keys get theirs`() {
        val profile = TypingProfile.NORMAL
        val schedule = ReportScheduler.schedule(mapper.map("a\nb").strokes, profile)

        // The gap preceding 'b' carries interKey + the post-newline extra.
        val bDown = schedule.first { it.strokeIndex == 2 }
        assertEquals(profile.interKeyDelayMs + profile.newlineExtraDelayMs, bDown.delayBeforeMs)
    }

    @Test
    fun `first stroke has no leading delay`() {
        val schedule = ReportScheduler.schedule(mapper.map("abc").strokes, TypingProfile.NORMAL)
        assertEquals(0, schedule.first().delayBeforeMs)
    }

    @Test
    fun `key hold time precedes the release`() {
        val profile = TypingProfile.BIOS
        val schedule = ReportScheduler.schedule(mapper.map("a").strokes, profile)
        assertEquals(profile.keyHoldMs, schedule[1].delayBeforeMs)
    }

    @Test
    fun `humanize is ignored unless the profile enables it`() {
        // Distinct characters on purpose: a repeated key carries an extra gap,
        // which would mask what this test is measuring.
        val strokes = mapper.map("abcdefgh").strokes
        val alwaysZero = Jitter { 0 }

        val plain = ReportScheduler.schedule(strokes, TypingProfile.NORMAL, alwaysZero)
        assertTrue(
            plain.any { it.delayBeforeMs == TypingProfile.NORMAL.interKeyDelayMs },
            "jitter must not apply when humanize is off",
        )

        val humanized = TypingProfile.NORMAL.copy(humanize = true)
        val jittered = ReportScheduler.schedule(strokes, humanized, alwaysZero)
        assertTrue(jittered.none { it.delayBeforeMs == humanized.interKeyDelayMs })
    }

    @Test
    fun `duration estimate scales with the profile`() {
        val strokes = mapper.map("abcdefghij".repeat(10)).strokes
        val fast = ReportScheduler.estimateDurationMs(strokes, TypingProfile.FAST)
        val bios = ReportScheduler.estimateDurationMs(strokes, TypingProfile.BIOS)
        assertTrue(bios > fast * 3, "BIOS profile should be dramatically slower")
    }
}

class TypingPacerTest {

    private val mapper = LayoutMapper(BuiltInLayouts.US_QWERTY)

    @Test
    fun `text arrives at the host byte-identical`() = runTest {
        val transport = FakeHidTransport(TransportState.CONNECTED)
        val pacer = TypingPacer(transport, FakePacerClock())
        val text = "sudo systemctl restart nginx\nOK: 100% (a+b)"

        val outcome = pacer.send(
            ReportScheduler.schedule(mapper.map(text).strokes, TypingProfile.NORMAL),
        )

        assertIs<SendOutcome.Completed>(outcome)
        assertEquals(text, transport.decodeTypedText(BuiltInLayouts.US_QWERTY))
    }

    @Test
    fun `progress counts each completed stroke once`() = runTest {
        val transport = FakeHidTransport(TransportState.CONNECTED)
        val pacer = TypingPacer(transport, FakePacerClock())
        val strokes = mapper.map("Hello").strokes

        val progress = mutableListOf<Int>()
        pacer.send(ReportScheduler.schedule(strokes, TypingProfile.NORMAL)) { progress += it }

        assertEquals(listOf(1, 2, 3, 4, 5), progress)
    }

    @Test
    fun `a failed send releases all keys and reports how far it got`() = runTest {
        val transport = FakeHidTransport(TransportState.CONNECTED)
        val pacer = TypingPacer(transport, FakePacerClock())
        transport.failSendAtIndex = 7

        val outcome = pacer.send(
            ReportScheduler.schedule(mapper.map("abcdefgh").strokes, TypingProfile.NORMAL),
        )

        assertIs<SendOutcome.Failed>(outcome)
        assertTrue(transport.reports.last().isRelease, "keys must be released after a failure")
    }

    @Test
    fun `cancellation releases all keys`() = runTest {
        val transport = FakeHidTransport(TransportState.CONNECTED)
        val gate = CompletableDeferred<Unit>()
        val clock = object : PacerClock {
            override fun elapsedNanos() = 0L
            override suspend fun sleepUntil(targetNanos: Long) {
                gate.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val pacer = TypingPacer(transport, clock)

        val job = launch {
            pacer.send(ReportScheduler.schedule(mapper.map("abc").strokes, TypingProfile.NORMAL))
        }
        gate.await()
        job.cancelAndJoin()

        assertTrue(
            transport.reports.isEmpty() || transport.reports.last().isRelease,
            "a cancelled send must not leave a modifier asserted",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a slow transport does not compound into drift`() = runTest {
        val clock = FakePacerClock()
        val transport = object : HidTransport by FakeHidTransport(TransportState.CONNECTED) {
            override suspend fun sendKeyboardReport(report: KeyboardReport): Result<Unit> {
                clock.advanceMs(50) // every write takes far longer than the gap
                return Result.success(Unit)
            }
        }
        val pacer = TypingPacer(transport, clock)

        pacer.send(ReportScheduler.schedule(mapper.map("abcde").strokes, TypingProfile.FAST))

        assertTrue(
            clock.sleptForMs.all { it <= TypingProfile.FAST.interKeyDelayMs },
            "the pacer must not try to claw back time it has already lost: ${clock.sleptForMs}",
        )
    }

    @Test
    fun `sending nothing succeeds without touching the transport`() = runTest {
        val transport = FakeHidTransport(TransportState.CONNECTED)
        val outcome = TypingPacer(transport, FakePacerClock()).send(emptyList())
        assertIs<SendOutcome.Completed>(outcome)
        assertEquals(0, outcome.strokesSent)
        assertTrue(transport.reports.isEmpty())
    }
}
