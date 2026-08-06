package dev.cwtf.hidandseek.data.chat

import java.util.UUID

enum class ChatRole {
    USER, ASSISTANT, SYSTEM;

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.name == value } ?: USER
    }
}

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val pinned: Boolean = false,
    val providerId: String? = null,
    val model: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val error: String? = null,
    /** A stream that ended early. The partial content is kept, not discarded. */
    val incomplete: Boolean = false,
    val attachments: List<MessageAttachment> = emptyList(),
)

data class MessageAttachment(
    val id: String,
    val messageId: String,
    val localPath: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long,
    val ocrText: String? = null,
    /** The file was purged but the message kept; shown as a placeholder. */
    val deleted: Boolean = false,
)

/** A message split into prose and fenced code, for rendering and per-block actions. */
sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class Code(val language: String?, val code: String) : MessageSegment
}

/**
 * Splits markdown into prose and fenced code blocks.
 *
 * Deliberately minimal: the only structure this app needs to act on is the
 * code fence, because that is what gets its own "type to host" action. An
 * unterminated fence — common mid-stream — is treated as code to the end, so a
 * block being generated renders as code from the first line rather than
 * flickering from prose to code when the closing fence arrives.
 */
fun parseSegments(markdown: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val lines = markdown.lines()

    var index = 0
    val prose = StringBuilder()

    fun flushProse() {
        if (prose.isNotEmpty()) {
            val text = prose.toString().trim('\n')
            if (text.isNotBlank()) segments += MessageSegment.Text(text)
            prose.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            flushProse()
            val language = line.trimStart().removePrefix("```").trim().ifBlank { null }
            val code = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                code.appendLine(lines[index])
                index++
            }
            index++ // consume the closing fence, if there was one
            segments += MessageSegment.Code(language, code.toString().trimEnd('\n'))
        } else {
            prose.appendLine(line)
            index++
        }
    }
    flushProse()

    return segments
}
