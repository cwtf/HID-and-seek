package dev.cwtf.hidandseek.data.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import dev.cwtf.hidandseek.data.agent.AgentAuditEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Chat history.
 *
 * Kept forever by design: nothing here expires or prunes on a timer. Deletion
 * is always something the user asked for, which is why the delete methods are
 * the only way content ever leaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(context: Context) {

    private val helper = ChatDatabase(context.applicationContext)

    /**
     * Bumped after every write.
     *
     * Plain SQLite has no change notifications, so queries re-run off this
     * rather than the UI having to remember to refresh.
     */
    private val revision = MutableStateFlow(0L)

    private fun invalidate() {
        revision.value = revision.value + 1
    }

    // --- conversations ------------------------------------------------------

    val conversations: Flow<List<Conversation>> =
        revision.flatMapLatest { flow { emit(loadConversations()) } }

    private suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """
            SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS n
            FROM conversations c
            ORDER BY c.pinned DESC, c.updated_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toConversation())
            }
        }
    }

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        revision.flatMapLatest { flow { emit(loadMessages(conversationId)) } }

    private suspend fun loadMessages(conversationId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val db = helper.readableDatabase

            // Attachments for the whole conversation in one query, then grouped
            // — one query per message would be a round trip per bubble.
            val attachmentsByMessage = db.rawQuery(
                """
                SELECT a.* FROM attachments a
                JOIN messages m ON m.id = a.message_id
                WHERE m.conversation_id = ?
                """.trimIndent(),
                arrayOf(conversationId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toAttachment())
                }
            }.groupBy { it.messageId }

            db.rawQuery(
                "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at ASC",
                arrayOf(conversationId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val message = cursor.toMessage()
                        add(
                            message.copy(
                                attachments = attachmentsByMessage[message.id].orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

    suspend fun addAttachment(attachment: MessageAttachment) = write { db ->
        db.insert(
            "attachments",
            null,
            ContentValues().apply {
                put("id", attachment.id)
                put("message_id", attachment.messageId)
                put("local_path", attachment.localPath)
                put("mime_type", attachment.mimeType)
                put("width_px", attachment.widthPx)
                put("height_px", attachment.heightPx)
                put("byte_size", attachment.byteSize)
                put("ocr_text", attachment.ocrText)
                put("deleted", if (attachment.deleted) 1 else 0)
            },
        )
    }

    /** Marks every image purged, keeping the messages that carried them. */
    suspend fun markAllAttachmentsDeleted() = write { db ->
        db.execSQL("UPDATE attachments SET deleted = 1")
    }

    suspend fun createConversation(
        title: String,
        providerId: String?,
        model: String?,
    ): Conversation = withContext(Dispatchers.IO) {
        val conversation = Conversation(title = title, providerId = providerId, model = model)
        helper.writableDatabase.insert(
            "conversations",
            null,
            ContentValues().apply {
                put("id", conversation.id)
                put("title", conversation.title)
                put("pinned", 0)
                put("provider_id", providerId)
                put("model", model)
                put("created_at", conversation.createdAtEpochMs)
                put("updated_at", conversation.updatedAtEpochMs)
            },
        )
        invalidate()
        conversation
    }

    suspend fun renameConversation(id: String, title: String) = write {
        it.update(
            "conversations",
            ContentValues().apply { put("title", title) },
            "id = ?",
            arrayOf(id),
        )
    }

    suspend fun setPinned(id: String, pinned: Boolean) = write {
        it.update(
            "conversations",
            ContentValues().apply { put("pinned", if (pinned) 1 else 0) },
            "id = ?",
            arrayOf(id),
        )
    }

    suspend fun setConversationModel(id: String, providerId: String?, model: String?) = write {
        it.update(
            "conversations",
            ContentValues().apply {
                put("provider_id", providerId)
                put("model", model)
            },
            "id = ?",
            arrayOf(id),
        )
    }

    /** Cascades to the conversation's messages and their search index entries. */
    suspend fun deleteConversation(id: String) = write {
        it.delete("conversations", "id = ?", arrayOf(id))
    }

    /**
     * Removes everything, then VACUUMs.
     *
     * Without the VACUUM the deleted rows stay legible in the file's free
     * pages, which makes "delete all my chat history" a half-truth.
     */
    suspend fun deleteAllConversations() = withContext(Dispatchers.IO) {
        helper.writableDatabase.apply {
            delete("conversations", null, null)
            execSQL("VACUUM")
        }
        invalidate()
    }

    // --- messages -----------------------------------------------------------

    suspend fun addMessage(message: ChatMessage): ChatMessage = withContext(Dispatchers.IO) {
        helper.writableDatabase.apply {
            insert("messages", null, message.toValues())
            update(
                "conversations",
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id = ?",
                arrayOf(message.conversationId),
            )
        }
        invalidate()
        message
    }

    suspend fun updateMessage(message: ChatMessage) = write {
        it.update("messages", message.toValues(), "id = ?", arrayOf(message.id))
    }

    suspend fun deleteMessage(id: String) = write {
        it.delete("messages", "id = ?", arrayOf(id))
    }

    /** Deletes a message and everything after it — used by regenerate. */
    suspend fun deleteFrom(conversationId: String, fromCreatedAtEpochMs: Long) = write {
        it.delete(
            "messages",
            "conversation_id = ? AND created_at >= ?",
            arrayOf(conversationId, fromCreatedAtEpochMs.toString()),
        )
    }

    // --- search -------------------------------------------------------------

    suspend fun search(query: String): List<Pair<Conversation, ChatMessage>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            helper.readableDatabase.rawQuery(
                """
                SELECT m.*, c.id AS c_id, c.title AS c_title, c.pinned AS c_pinned,
                       c.provider_id AS c_provider_id, c.model AS c_model,
                       c.created_at AS c_created_at, c.updated_at AS c_updated_at
                FROM messages_fts
                JOIN messages m ON m.rowid = messages_fts.docid
                JOIN conversations c ON c.id = m.conversation_id
                WHERE messages_fts MATCH ?
                ORDER BY m.created_at DESC
                LIMIT 100
                """.trimIndent(),
                arrayOf(query),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val conversation = Conversation(
                            id = cursor.string("c_id"),
                            title = cursor.string("c_title"),
                            pinned = cursor.int("c_pinned") == 1,
                            providerId = cursor.stringOrNull("c_provider_id"),
                            model = cursor.stringOrNull("c_model"),
                            createdAtEpochMs = cursor.long("c_created_at"),
                            updatedAtEpochMs = cursor.long("c_updated_at"),
                        )
                        add(conversation to cursor.toMessage())
                    }
                }
            }
        }

    // --- agent audit --------------------------------------------------------

    /**
     * Every agent typing request, allowed or not.
     *
     * Written in all modes including Ask, so there is always a record of what
     * a model asked to put into a machine — not only what it managed to.
     */
    suspend fun recordAgentEvent(entry: AgentAuditEntry) = write { db ->
        db.insert(
            "agent_audit",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("device_address", entry.deviceAddress)
                put("mode", entry.mode.name)
                put("preview", entry.preview)
                put("char_count", entry.charCount)
                put("approved", if (entry.approved) 1 else 0)
                put("result", entry.result)
                put("at", entry.atEpochMs)
            },
        )
    }

    val agentAudit: Flow<List<AgentAuditEntry>> =
        revision.flatMapLatest { flow { emit(loadAudit()) } }

    private suspend fun loadAudit(): List<AgentAuditEntry> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM agent_audit ORDER BY at DESC LIMIT 200",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        AgentAuditEntry(
                            id = cursor.string("id"),
                            deviceAddress = cursor.stringOrNull("device_address"),
                            mode = runCatching {
                                dev.cwtf.hidandseek.data.agent.AgentMode.valueOf(
                                    cursor.string("mode"),
                                )
                            }.getOrDefault(dev.cwtf.hidandseek.data.agent.AgentMode.ASK),
                            preview = cursor.string("preview"),
                            charCount = cursor.int("char_count"),
                            approved = cursor.int("approved") == 1,
                            result = cursor.string("result"),
                            atEpochMs = cursor.long("at"),
                        ),
                    )
                }
            }
        }
    }

    suspend fun clearAgentAudit() = write { it.delete("agent_audit", null, null) }

    // --- statistics, for the data settings screen ---------------------------

    suspend fun stats(): ChatStats = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val conversations = db.rawQuery("SELECT COUNT(*) FROM conversations", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val messages = db.rawQuery("SELECT COUNT(*) FROM messages", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        ChatStats(conversations, messages, db.path?.let { java.io.File(it).length() } ?: 0L)
    }

    private suspend fun write(block: (android.database.sqlite.SQLiteDatabase) -> Unit) =
        withContext(Dispatchers.IO) {
            block(helper.writableDatabase)
            invalidate()
        }
}

data class ChatStats(val conversations: Int, val messages: Int, val databaseBytes: Long)

// --- cursor helpers ---------------------------------------------------------

private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
private fun Cursor.stringOrNull(column: String) =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
private fun Cursor.intOrNull(column: String) =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

private fun Cursor.toConversation() = Conversation(
    id = string("id"),
    title = string("title"),
    pinned = int("pinned") == 1,
    providerId = stringOrNull("provider_id"),
    model = stringOrNull("model"),
    createdAtEpochMs = long("created_at"),
    updatedAtEpochMs = long("updated_at"),
    messageCount = runCatching { int("n") }.getOrDefault(0),
)

private fun Cursor.toMessage() = ChatMessage(
    id = string("id"),
    conversationId = string("conversation_id"),
    role = ChatRole.parse(string("role")),
    content = string("content"),
    promptTokens = intOrNull("prompt_tokens"),
    completionTokens = intOrNull("completion_tokens"),
    createdAtEpochMs = long("created_at"),
    error = stringOrNull("error"),
    incomplete = int("incomplete") == 1,
)

private fun Cursor.toAttachment() = MessageAttachment(
    id = string("id"),
    messageId = string("message_id"),
    localPath = string("local_path"),
    mimeType = string("mime_type"),
    widthPx = int("width_px"),
    heightPx = int("height_px"),
    byteSize = long("byte_size"),
    ocrText = stringOrNull("ocr_text"),
    deleted = int("deleted") == 1,
)

private fun ChatMessage.toValues() = ContentValues().apply {
    put("id", id)
    put("conversation_id", conversationId)
    put("role", role.name)
    put("content", content)
    put("prompt_tokens", promptTokens)
    put("completion_tokens", completionTokens)
    put("created_at", createdAtEpochMs)
    put("error", error)
    put("incomplete", if (incomplete) 1 else 0)
}
