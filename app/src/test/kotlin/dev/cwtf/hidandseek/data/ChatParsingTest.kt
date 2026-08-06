package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.data.chat.MessageSegment
import dev.cwtf.hidandseek.data.chat.parseSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Code-fence splitting.
 *
 * This decides what the per-block "type to host" action sends, so a fence
 * parsed wrongly means the wrong text lands in someone's terminal.
 */
class ChatParsingTest {

    @Test
    fun `plain prose is one text segment`() {
        val segments = parseSegments("Just some words.\nAcross two lines.")
        assertEquals(1, segments.size)
        assertIs<MessageSegment.Text>(segments.single())
    }

    @Test
    fun `a fenced block is separated from the prose around it`() {
        val segments = parseSegments(
            """
            You can use:
            ```bash
            ls -la /var/log
            ```
            That lists them.
            """.trimIndent(),
        )

        assertEquals(3, segments.size)
        assertEquals("You can use:", assertIs<MessageSegment.Text>(segments[0]).text)

        val code = assertIs<MessageSegment.Code>(segments[1])
        assertEquals("bash", code.language)
        assertEquals("ls -la /var/log", code.code)

        assertEquals("That lists them.", assertIs<MessageSegment.Text>(segments[2]).text)
    }

    @Test
    fun `code content is exact, including indentation`() {
        val segments = parseSegments(
            "```python\ndef f():\n    return 1\n```",
        )
        val code = assertIs<MessageSegment.Code>(segments.single())
        assertEquals("def f():\n    return 1", code.code)
    }

    @Test
    fun `a fence with no language still parses`() {
        val code = assertIs<MessageSegment.Code>(parseSegments("```\nplain\n```").single())
        assertEquals(null, code.language)
        assertEquals("plain", code.code)
    }

    @Test
    fun `an unterminated fence is treated as code to the end`() {
        // Happens on every streamed reply: the closing fence has not arrived
        // yet, and the block should not flicker from prose into code.
        val segments = parseSegments("Here:\n```bash\nsudo systemctl restart ngin")
        val code = assertIs<MessageSegment.Code>(segments[1])
        assertEquals("sudo systemctl restart ngin", code.code)
    }

    @Test
    fun `multiple blocks are kept separate`() {
        val segments = parseSegments(
            "One:\n```\nalpha\n```\nTwo:\n```\nbeta\n```",
        )
        val codes = segments.filterIsInstance<MessageSegment.Code>()
        assertEquals(listOf("alpha", "beta"), codes.map { it.code })
    }

    @Test
    fun `blank prose between blocks is dropped`() {
        val segments = parseSegments("```\na\n```\n\n\n```\nb\n```")
        assertTrue(segments.all { it is MessageSegment.Code })
        assertEquals(2, segments.size)
    }

    @Test
    fun `empty input produces nothing`() {
        assertTrue(parseSegments("").isEmpty())
    }
}
