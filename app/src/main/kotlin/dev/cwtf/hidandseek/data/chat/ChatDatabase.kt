package dev.cwtf.hidandseek.data.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Chat storage.
 *
 * Hand-written rather than Room: KSP has no build for Kotlin 2.4.10, so Room's
 * annotation processor cannot run. Plain SQLite still gives everything the
 * feature needs — foreign keys with cascade delete, a full-text index for
 * search, and VACUUM so a "delete everything" really reclaims the pages rather
 * than leaving content readable in free space.
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Off by default in Android's SQLite; without this the cascade below
        // silently does nothing and deleted conversations leak their messages.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                provider_id TEXT,
                model TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                created_at INTEGER NOT NULL,
                error TEXT,
                incomplete INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at)")

        db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(content, tokenize=unicode61)")

        // Triggers keep the index in step with the table, so search cannot
        // return rows that no longer exist or miss ones that do.
        db.execSQL(
            """
            CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(docid, content) VALUES (new.rowid, new.content);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN
                DELETE FROM messages_fts WHERE docid = old.rowid;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER messages_fts_update AFTER UPDATE ON messages BEGIN
                UPDATE messages_fts SET content = new.content WHERE docid = new.rowid;
            END
            """.trimIndent(),
        )

        createAgentAudit(db)
        createAttachments(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive only. Chat history is kept forever, so an upgrade must never
        // drop a table — a migration that loses data is worse than no feature.
        if (oldVersion < 2) {
            createAgentAudit(db)
            createAttachments(db)
        }
    }

    private fun createAgentAudit(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_audit (
                id TEXT PRIMARY KEY NOT NULL,
                device_address TEXT,
                mode TEXT NOT NULL,
                preview TEXT NOT NULL,
                char_count INTEGER NOT NULL,
                approved INTEGER NOT NULL,
                result TEXT NOT NULL,
                at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_audit_at ON agent_audit(at DESC)")
    }

    private fun createAttachments(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY NOT NULL,
                message_id TEXT NOT NULL,
                local_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                width_px INTEGER NOT NULL,
                height_px INTEGER NOT NULL,
                byte_size INTEGER NOT NULL,
                ocr_text TEXT,
                deleted INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_message ON attachments(message_id)")
    }

    companion object {
        const val NAME = "chat.db"
        const val VERSION = 2
    }
}
