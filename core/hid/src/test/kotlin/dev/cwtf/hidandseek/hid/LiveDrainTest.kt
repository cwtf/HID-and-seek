package dev.cwtf.hidandseek.hid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The drain state machine is the highest-risk part of the app: it is the only
 * component that can delete text on someone else's machine. These tests cover
 * the matrix in SPEC §11.1.
 */
class LiveDrainTest {

    private fun drain(
        settleDelayMs: Int = 400,
        retractionCap: Int? = 64,
        overCap: OverCapAction = OverCapAction.ASK,
        flushOnSpace: Boolean = true,
        threshold: Int? = 120,
    ) = LiveDrain(
        LiveTypingConfig(
            settleDelayMs = settleDelayMs,
            flushOnSpace = flushOnSpace,
            pendingFlushThreshold = threshold,
            retractionCap = retractionCap,
            overCapAction = overCap,
        ),
    )

    // --- appending ----------------------------------------------------------

    @Test
    fun `typing defers until the buffer settles`() {
        val d = drain()
        val decision = d.onEdit("hello")

        val deferred = assertIs<DrainDecision.Defer>(decision)
        assertEquals(400, deferred.afterMs)
        assertEquals(DrainPlan.Type("hello"), deferred.plan)
        assertEquals("", d.sentText, "nothing reaches the host before the settle delay")
    }

    @Test
    fun `settle timer flushes the pending text`() {
        val d = drain()
        d.onEdit("hello")

        val decision = d.onSettleElapsed("hello")
        val execute = assertIs<DrainDecision.Execute>(decision)
        assertEquals(DrainPlan.Type("hello"), execute.plan)

        d.onExecuted(execute.plan)
        assertEquals("hello", d.sentText)
    }

    @Test
    fun `only the newly added text is sent, not the whole buffer`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))

        val decision = d.onEdit("hello world", trigger = EditTrigger.MANUAL_FLUSH)
        val execute = assertIs<DrainDecision.Execute>(decision)
        assertEquals(DrainPlan.Type(" world"), execute.plan)
    }

    @Test
    fun `space flushes immediately when configured`() {
        val d = drain()
        assertIs<DrainDecision.Execute>(d.onEdit("hi ", trigger = EditTrigger.SPACE))
    }

    @Test
    fun `space defers when flush-on-space is off`() {
        val d = drain(flushOnSpace = false)
        assertIs<DrainDecision.Defer>(d.onEdit("hi ", trigger = EditTrigger.SPACE))
    }

    @Test
    fun `enter flushes immediately`() {
        val d = drain()
        assertIs<DrainDecision.Execute>(d.onEdit("hi\n", trigger = EditTrigger.ENTER))
    }

    @Test
    fun `crossing the pending threshold forces a flush`() {
        val d = drain(threshold = 20)
        assertIs<DrainDecision.Defer>(d.onEdit("a".repeat(19)))
        assertIs<DrainDecision.Execute>(d.onEdit("a".repeat(20)))
    }

    // --- composing region ---------------------------------------------------

    @Test
    fun `text inside the composing region is never transmitted`() {
        val d = drain()
        // "sudo ngin" committed, "x" still being composed by the IME.
        val decision = d.onEdit("sudo nginx", compositionStart = 9, trigger = EditTrigger.MANUAL_FLUSH)

        val execute = assertIs<DrainDecision.Execute>(decision)
        assertEquals(DrainPlan.Type("sudo ngin"), execute.plan)
    }

    @Test
    fun `autocorrect churn inside the composing region costs nothing`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("the "))

        // Gboard rewrites the candidate repeatedly while composing.
        assertEquals(DrainPlan.Idle, d.plan("the teh", compositionStart = 4))
        assertEquals(DrainPlan.Idle, d.plan("the the", compositionStart = 4))
        assertEquals(DrainPlan.Idle, d.plan("the there", compositionStart = 4))
        assertEquals("the ", d.sentText, "no candidate leaked to the host")

        // Commit: composition clears and the settled word goes out whole.
        assertEquals(DrainPlan.Type("there"), d.plan("the there", compositionStart = null))
    }

    @Test
    fun `a composing region overlapping sent text retracts back to it`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello world"))

        // The user taps back into "world" and the IME starts composing it.
        val plan = d.plan("hello world", compositionStart = 6)
        val retract = assertIs<DrainPlan.Retract>(plan)
        assertEquals(5, retract.backspaces, "the composing word is pulled back off the host")
        assertEquals("", retract.thenType)
    }

    // --- divergence ---------------------------------------------------------

    @Test
    fun `backspacing already-sent text retracts on the host`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))

        val plan = d.plan("hel")
        assertEquals(DrainPlan.Retract(2, ""), plan)

        d.onExecuted(plan)
        assertEquals("hel", d.sentText)
    }

    @Test
    fun `autocorrect rewriting a finished word retracts only the difference`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("sudo systemctl restart nginx"))

        // The user changes nginx to apache.
        val plan = d.plan("sudo systemctl restart apache")
        val retract = assertIs<DrainPlan.Retract>(plan)
        assertEquals(5, retract.backspaces, "only 'nginx' comes off, not the whole line")
        assertEquals("apache", retract.thenType)

        d.onExecuted(plan)
        assertEquals("sudo systemctl restart apache", d.sentText)
    }

    @Test
    fun `an edit in the middle of the buffer retypes the tail`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("abcdef"))

        val plan = d.plan("abXdef")
        val retract = assertIs<DrainPlan.Retract>(plan)
        assertEquals(4, retract.backspaces)
        assertEquals("Xdef", retract.thenType)

        d.onExecuted(plan)
        assertEquals("abXdef", d.sentText)
    }

    @Test
    fun `clearing the buffer retracts everything`() {
        val d = drain(retractionCap = null)
        d.onExecuted(DrainPlan.Type("hello"))
        assertEquals(DrainPlan.Retract(5, ""), d.plan(""))
    }

    @Test
    fun `an unchanged buffer plans nothing`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))
        assertEquals(DrainPlan.Idle, d.plan("hello"))
        assertEquals(DrainDecision.Idle, d.onEdit("hello"))
    }

    // --- retraction cap -----------------------------------------------------

    @Test
    fun `a retraction within the cap executes without asking`() {
        val d = drain(retractionCap = 10)
        d.onExecuted(DrainPlan.Type("hello world"))
        assertIs<DrainDecision.Execute>(d.onManualFlush("hello "))
    }

    @Test
    fun `a retraction beyond the cap asks instead of deleting host text`() {
        val d = drain(retractionCap = 4)
        d.onExecuted(DrainPlan.Type("hello world"))

        val decision = d.onManualFlush("h")
        val ask = assertIs<DrainDecision.AskOverCap>(decision)
        assertEquals(10, ask.plan.backspaces)
        assertEquals(4, ask.cap)
        assertEquals("hello world", d.sentText, "state is untouched until the user chooses")
    }

    @Test
    fun `always-retype skips the prompt`() {
        val d = drain(retractionCap = 4, overCap = OverCapAction.ALWAYS_RETYPE)
        d.onExecuted(DrainPlan.Type("hello world"))

        val execute = assertIs<DrainDecision.Execute>(d.onManualFlush("h"))
        assertIs<DrainPlan.Retract>(execute.plan)
    }

    @Test
    fun `always-skip resyncs without sending anything`() {
        val d = drain(retractionCap = 4, overCap = OverCapAction.ALWAYS_SKIP_AND_RESYNC)
        d.onExecuted(DrainPlan.Type("hello world"))

        val execute = assertIs<DrainDecision.Execute>(d.onManualFlush("h"))
        val resync = assertIs<DrainPlan.Resync>(execute.plan)

        d.onExecuted(resync)
        assertEquals(
            "hello world",
            d.sentText,
            "the host keeps what it had; we simply stop trying to fix it",
        )
        assertEquals(DrainPlan.Idle, d.plan("hello world"))
    }

    @Test
    fun `an unlimited cap never asks`() {
        val d = drain(retractionCap = null)
        d.onExecuted(DrainPlan.Type("a".repeat(500)))
        assertIs<DrainDecision.Execute>(d.onManualFlush(""))
    }

    // --- partial execution --------------------------------------------------

    @Test
    fun `a send that dies halfway leaves sentText describing what the host really holds`() {
        val d = drain()
        val plan = assertIs<DrainDecision.Execute>(d.onManualFlush("hello world")).plan
        assertIs<DrainPlan.Type>(plan)

        // The link drops after six characters.
        d.onTextTyped("hello ")
        d.onDisconnected()

        assertEquals("hello ", d.sentText)
        assertEquals(
            DrainPlan.Type("world"),
            d.plan("hello world"),
            "the remainder is what is still owed, with no bookkeeping of its own",
        )
    }

    @Test
    fun `partial retraction is tracked backspace by backspace`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))

        d.onBackspacesApplied(2)
        assertEquals("hel", d.sentText)
    }

    // --- connection ---------------------------------------------------------

    @Test
    fun `edits while disconnected are held, not lost`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))
        d.onDisconnected()

        assertEquals(DrainDecision.Idle, d.onEdit("hello world"))
        assertEquals(
            DrainPlan.Type(" world"),
            d.plan("hello world"),
            "the plan still knows what is owed",
        )
    }

    @Test
    fun `resume sends everything that piled up while disconnected`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))
        d.onDisconnected()
        d.onEdit("hello world, and more")

        val decision = d.onReconnected(ReconnectPolicy.RESUME, "hello world, and more")
        val execute = assertIs<DrainDecision.Execute>(decision)
        assertEquals(DrainPlan.Type(" world, and more"), execute.plan)
    }

    @Test
    fun `reset watermark abandons the queued text`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))
        d.onDisconnected()

        val decision = d.onReconnected(ReconnectPolicy.RESET_WATERMARK, "hello world")
        assertEquals(DrainDecision.Idle, decision)
        assertEquals("hello world", d.sentText)
        assertEquals(DrainPlan.Idle, d.plan("hello world"))
    }

    @Test
    fun `ask on reconnect defers to the user`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))
        d.onDisconnected()

        assertEquals(
            DrainDecision.Idle,
            d.onReconnected(ReconnectPolicy.ASK, "hello world"),
        )
        assertTrue(d.isConnected)
        assertEquals(DrainPlan.Type(" world"), d.plan("hello world"))
    }

    // --- mode switching -----------------------------------------------------

    @Test
    fun `entering live mode can assume the host already has the staged text`() {
        val d = drain()
        d.assumeHostHolds("already sent")
        assertEquals(DrainPlan.Idle, d.plan("already sent"))
        assertEquals(DrainPlan.Type(" plus more"), d.plan("already sent plus more"))
    }

    @Test
    fun `reset makes the whole buffer pending again`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("hello"))
        d.reset()
        assertEquals(DrainPlan.Type("hello"), d.plan("hello"))
    }

    @Test
    fun `config changes take effect without restarting live mode`() {
        val d = drain(settleDelayMs = 400)
        assertEquals(400, assertIs<DrainDecision.Defer>(d.onEdit("a")).afterMs)

        d.config = d.config.copy(settleDelayMs = 50)
        assertEquals(50, assertIs<DrainDecision.Defer>(d.onEdit("ab")).afterMs)
    }

    // --- unicode ------------------------------------------------------------

    @Test
    fun `backspace counts are code points, not UTF-16 units`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("ab🎉"))

        val plan = d.plan("ab")
        assertEquals(
            DrainPlan.Retract(1, ""),
            plan,
            "an emoji is one character to the host, not two",
        )
    }

    @Test
    fun `common prefix does not split a surrogate pair`() {
        val d = drain()
        d.onExecuted(DrainPlan.Type("a🎉b"))
        val plan = assertIs<DrainPlan.Retract>(d.plan("a🎈b"))
        assertEquals(2, plan.backspaces)
        assertEquals("🎈b", plan.thenType)
    }

    // --- pending count ------------------------------------------------------

    @Test
    fun `pending count reflects what is owed to the host`() {
        val d = drain()
        assertEquals(0, d.pendingCount(""))
        assertEquals(5, d.pendingCount("hello"))

        d.onExecuted(DrainPlan.Type("hello"))
        assertEquals(0, d.pendingCount("hello"))
        assertEquals(6, d.pendingCount("hello world"))
        assertEquals(0, d.pendingCount("hello world", compositionStart = 5))
    }

    // --- configuration validation -------------------------------------------

    @Test
    fun `configuration rejects out-of-range values`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LiveTypingConfig(settleDelayMs = 5_000)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LiveTypingConfig(retractionCap = 5_000)
        }
    }
}
