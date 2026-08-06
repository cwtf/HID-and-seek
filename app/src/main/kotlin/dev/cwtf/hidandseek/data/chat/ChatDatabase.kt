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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing has shipped yet, so there is no migration history to honour.
        // Once a build is in someone's hands this must become real migrations —
        // chat history is kept forever and must never be dropped by an upgrade.
        db.execSQL("DROP TRIGGER IF EXISTS messages_fts_insert")
        db.execSQL("DROP TRIGGER IF EXISTS messages_fts_delete")
        db.execSQL("DROP TRIGGER IF EXISTS messages_fts_update")
        db.execSQL("DROP TABLE IF EXISTS messages_fts")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        onCreate(db)
    }

    companion object {
        const val NAME = "chat.db"
        const val VERSION = 1
    }
}
