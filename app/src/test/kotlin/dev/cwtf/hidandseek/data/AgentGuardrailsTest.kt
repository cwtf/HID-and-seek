package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.data.agent.AgentDecision
import dev.cwtf.hidandseek.data.agent.AgentGuardrails
import dev.cwtf.hidandseek.data.agent.AgentMode
import dev.cwtf.hidandseek.data.agent.AgentRateState
import dev.cwtf.hidandseek.data.agent.AgentSettings
import dev.cwtf.hidandseek.data.agent.ApprovalReason
import dev.cwtf.hidandseek.data.agent.TypeToHostRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules standing between a language model and someone's keyboard.
 *
 * Worth proving rather than eyeballing: a mistake here types into a machine the
 * user cannot see.
 */
class AgentGuardrailsTest {

    private val settings = AgentSettings()
    private val idle = AgentRateState()

    private fun request(text: String) = TypeToHostRequest(callId = "c1", text = text)

    private fun evaluate(
        text: String,
        mode: AgentMode = AgentMode.AUTO,
        settings: AgentSettings = this.settings,
        state: AgentRateState = idle,
    ) = AgentGuardrails.evaluate(request(text), mode, settings, state, nowEpochMs = 1_000_000)

    // --- modes --------------------------------------------------------------

    @Test
    fun `off denies everything`() {
        assertIs<AgentDecision.Deny>(evaluate("ls", mode = AgentMode.OFF))
    }

    @Test
    fun `ask always requires approval, even for harmless text`() {
        val decision = assertIs<AgentDecision.RequireApproval>(evaluate("ls", AgentMode.ASK))
        assertEquals(ApprovalReason.ASK_MODE, decision.reason)
    }

    @Test
    fun `auto allows ordinary text without asking`() {
        assertIs<AgentDecision.Allow>(evaluate("ls -la /var/log", AgentMode.AUTO))
    }

    // --- hard caps ----------------------------------------------------------

    @Test
    fun `the character cap holds even in ask mode`() {
        // A user cannot be talked into approving a 50,000-character paste,
        // because the cap is checked before the mode is considered.
        val decision = assertIs<AgentDecision.Deny>(
            evaluate("x".repeat(2_001), mode = AgentMode.ASK),
        )
        assertTrue(decision.reason.contains("2000"), decision.reason)
    }

    @Test
    fun `text exactly at the cap is allowed`() {
        assertIs<AgentDecision.Allow>(evaluate("x".repeat(2_000)))
    }

    @Test
    fun `empty text is refused`() {
        assertIs<AgentDecision.Deny>(evaluate(""))
    }

    @Test
    fun `the per-turn limit denies once reached, in every mode`() {
        val state = idle.copy(callsThisTurn = 3)
        assertIs<AgentDecision.Deny>(evaluate("ls", AgentMode.AUTO, state = state))
        assertIs<AgentDecision.Deny>(evaluate("ls", AgentMode.ASK, state = state))
    }

    @Test
    fun `the per-minute limit counts only recent calls`() {
        val now = 1_000_000L
        val recent = AgentRateState(recentCallTimestamps = List(10) { now - 1_000 })
        assertIs<AgentDecision.Deny>(
            AgentGuardrails.evaluate(request("ls"), AgentMode.AUTO, settings, recent, now),
        )

        val stale = AgentRateState(recentCallTimestamps = List(10) { now - 61_000 })
        assertIs<AgentDecision.Allow>(
            AgentGuardrails.evaluate(request("ls"), AgentMode.AUTO, settings, stale, now),
        )
    }

    // --- blocklist ----------------------------------------------------------

    @Test
    fun `destructive commands need confirmation even in auto`() {
        listOf(
            "rm -rf /",
            "sudo apt update",
            "mkfs.ext4 /dev/sda1",
            "dd if=/dev/zero of=/dev/sda",
            "format C:",
            "del /s C:\\",
        ).forEach { command ->
            val decision = evaluate(command, AgentMode.AUTO)
            val approval = assertIs<AgentDecision.RequireApproval>(
                decision,
                "\"$command\" should have needed confirmation",
            )
            assertEquals(ApprovalReason.BLOCKLISTED, approval.reason)
        }
    }

    @Test
    fun `credential-shaped text needs confirmation`() {
        assertIs<AgentDecision.RequireApproval>(evaluate("api_key=sk-abc123"))
        assertIs<AgentDecision.RequireApproval>(evaluate("PASSWORD: hunter2"))
    }

    @Test
    fun `ordinary commands are not caught by the blocklist`() {
        listOf("ls -la", "git status", "cd /var/log", "echo hello").forEach { command ->
            assertIs<AgentDecision.Allow>(evaluate(command), "\"$command\" was wrongly flagged")
        }
    }

    @Test
    fun `an invalid user pattern is ignored rather than breaking the rest`() {
        val broken = settings.copy(blocklist = listOf("[unclosed", """rm\s+-rf"""))
        assertIs<AgentDecision.RequireApproval>(evaluate("rm -rf /", settings = broken))
        assertIs<AgentDecision.Allow>(evaluate("ls", settings = broken))
    }

    @Test
    fun `an empty blocklist disables the check but not the caps`() {
        val none = settings.copy(blocklist = emptyList())
        assertIs<AgentDecision.Allow>(evaluate("rm -rf /", settings = none))
        assertIs<AgentDecision.Deny>(evaluate("x".repeat(3_000), settings = none))
    }

    // --- auto expiry --------------------------------------------------------

    @Test
    fun `auto lapses back to ask after the expiry window`() {
        val now = 1_000_000L
        val fresh = AgentRateState(autoSinceEpochMs = now - 60_000)
        assertEquals(
            AgentMode.AUTO,
            AgentGuardrails.effectiveMode(AgentMode.AUTO, fresh, settings, connected = true, now),
        )

        val expired = AgentRateState(autoSinceEpochMs = now - 16 * 60_000)
        assertEquals(
            AgentMode.ASK,
            AgentGuardrails.effectiveMode(AgentMode.AUTO, expired, settings, connected = true, now),
        )
    }

    @Test
    fun `auto does not survive a disconnect`() {
        val now = 1_000_000L
        val fresh = AgentRateState(autoSinceEpochMs = now)
        assertEquals(
            AgentMode.ASK,
            AgentGuardrails.effectiveMode(AgentMode.AUTO, fresh, settings, connected = false, now),
        )
    }

    @Test
    fun `auto without a start time falls back to ask`() {
        assertEquals(
            AgentMode.ASK,
            AgentGuardrails.effectiveMode(AgentMode.AUTO, idle, settings, connected = true),
        )
    }

    @Test
    fun `ask and off are unaffected by expiry`() {
        assertEquals(
            AgentMode.ASK,
            AgentGuardrails.effectiveMode(AgentMode.ASK, idle, settings, connected = true),
        )
        assertEquals(
            AgentMode.OFF,
            AgentGuardrails.effectiveMode(AgentMode.OFF, idle, settings, connected = false),
        )
    }

    // --- rate state ---------------------------------------------------------

    @Test
    fun `recording a call advances both counters`() {
        val after = idle.recordCall(1_000)
        assertEquals(1, after.callsThisTurn)
        assertEquals(1, after.callsInLastMinute(1_500))
    }

    @Test
    fun `a new turn resets the per-turn counter but not the per-minute one`() {
        val after = idle.recordCall(1_000).recordCall(1_100).startTurn()
        assertEquals(0, after.callsThisTurn)
        assertEquals(2, after.callsInLastMinute(1_500))
    }

    @Test
    fun `nothing reaches allow except through auto with every check passing`() {
        // Belt and braces: the only path to Allow is Auto, under the caps, off
        // the blocklist.
        assertFalse(evaluate("ls", AgentMode.OFF) is AgentDecision.Allow)
        assertFalse(evaluate("ls", AgentMode.ASK) is AgentDecision.Allow)
        assertFalse(evaluate("rm -rf /", AgentMode.AUTO) is AgentDecision.Allow)
        assertFalse(
            evaluate("ls", AgentMode.AUTO, state = idle.copy(callsThisTurn = 99))
                is AgentDecision.Allow,
        )
        assertTrue(evaluate("ls", AgentMode.AUTO) is AgentDecision.Allow)
    }
}
