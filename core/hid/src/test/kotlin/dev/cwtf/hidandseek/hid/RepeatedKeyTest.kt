package dev.cwtf.hidandseek.hid

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repeated identical characters.
 *
 * Reported from a real host: "ssss" arrived as "ss". These tests isolate which
 * layer is at fault — if the app produces four presses and the host shows two,
 * the loss is in transmission or host handling, not in the code here.
 */
class RepeatedKeyTest {

    private val layout = BuiltInLayouts.US_QWERTY
    private val mapper = LayoutMapper(layout)

    @Test
    fun `repeated characters produce one stroke each`() {
        val strokes = mapper.map("ssss").strokes
        assertEquals(4, strokes.size)
        assertTrue(strokes.all { it.usage == Usage.letter('s') })
    }

    @Test
    fun `repeated characters survive the whole pipeline`() = runTest {
        val transport = FakeHidTransport(TransportState.CONNECTED)
        val pacer = TypingPacer(transport, NoWaitClock())
        val text = "ssss aabbcc 111 hello"

        pacer.send(
            ReportScheduler.schedule(mapper.map(text).strokes, TypingProfile.NORMAL),
        )

        assertEquals(text, transport.decodeTypedText(layout))
    }

    @Test
    fun `every press is separated by a release`() {
        val schedule = ReportScheduler.schedule(mapper.map("ssss").strokes, TypingProfile.NORMAL)
        val reports = schedule.map { it.report }

        assertEquals(8, reports.size, "four presses and four releases")
        reports.forEachIndexed { index, report ->
            val expectPress = index % 2 == 0
            assertEquals(
                expectPress,
                report.keys.isNotEmpty(),
                "report $index should be a ${if (expectPress) "press" else "release"}",
            )
        }
    }

    @Test
    fun `a repeated key gets extra separation from the previous one`() {
        val profile = TypingProfile.NORMAL
        val schedule = ReportScheduler.schedule(mapper.map("sss").strokes, profile)

        // Gap preceding the second 's'.
        val secondPress = schedule.first { it.strokeIndex == 1 && it.report.keys.isNotEmpty() }
        assertEquals(
            profile.interKeyDelayMs + profile.repeatedKeyExtraDelayMs,
            secondPress.delayBeforeMs,
            "a re-press of the same key needs a longer gap than a different key",
        )
    }

    @Test
    fun `different consecutive keys keep the normal gap`() {
        val profile = TypingProfile.NORMAL
        val schedule = ReportScheduler.schedule(mapper.map("ab").strokes, profile)
        val secondPress = schedule.first { it.strokeIndex == 1 && it.report.keys.isNotEmpty() }
        assertEquals(profile.interKeyDelayMs, secondPress.delayBeforeMs)
    }

    @Test
    fun `case changes are not treated as repeats`() {
        // 's' then 'S' is the same usage code but with shift added; the host
        // still sees a re-press of that key, so it needs the same separation.
        val profile = TypingProfile.NORMAL
        val schedule = ReportScheduler.schedule(mapper.map("sS").strokes, profile)
        val modifierReport = schedule.first { it.strokeIndex == 1 }
        assertEquals(
            profile.interKeyDelayMs + profile.repeatedKeyExtraDelayMs,
            modifierReport.delayBeforeMs,
        )
    }

    @Test
    fun `repeated newlines are separated too`() {
        val profile = TypingProfile.NORMAL
        val schedule = ReportScheduler.schedule(mapper.map("a\n\nb").strokes, profile)
        val secondEnter = schedule.first { it.strokeIndex == 2 }
        assertEquals(
            profile.interKeyDelayMs + profile.newlineExtraDelayMs + profile.repeatedKeyExtraDelayMs,
            secondEnter.delayBeforeMs,
        )
    }
}

/** Runs the schedule without actually waiting. */
private class NoWaitClock : PacerClock {
    private var nanos = 0L
    override fun elapsedNanos() = nanos
    override suspend fun sleepUntil(targetNanos: Long) {
        nanos = targetNanos
    }
}
