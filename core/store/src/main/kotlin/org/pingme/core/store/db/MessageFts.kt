// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The full-text index over messages (BUILD_PLAN.md P1.2): an FTS5 table over the message
 * body, the sender's name, and the attachment file names, kept in sync by triggers.
 *
 * Room has no FTS5 entities, so the table and triggers are plain SQL created when the
 * database opens, and searches go through a raw query (MessageDao.searchIds). The table is
 * contentless (it stores only the index, not a second copy of every message) and keyed by
 * MessageEntity.rowId. FTS5 comes from the bundled SQLite; Android's own SQLite lacks it.
 */
internal object MessageFts {
    const val TABLE = "message_fts"

    /** One index row per message: rowid, body, sender name, attachment names. */
    private const val INDEX_ROWS = """
        SELECT m.rowId,
            coalesce(m.body, ''),
            coalesce((SELECT p.displayName FROM persons p WHERE p.id = m.senderId), ''),
            coalesce((SELECT group_concat(a.fileName, ' ') FROM attachments a
                      WHERE a.messageId = m.id AND a.fileName IS NOT NULL), '')
        FROM messages m"""

    private const val INSERT = "INSERT INTO $TABLE(rowid, body, sender_name, attachment_names)"

    private fun reindexMessage(messageIdExpr: String) =
        "DELETE FROM $TABLE WHERE rowid = (SELECT rowId FROM messages WHERE id = $messageIdExpr); " +
            "$INSERT $INDEX_ROWS WHERE m.id = $messageIdExpr;"

    private fun reindexSender(personIdExpr: String) =
        "DELETE FROM $TABLE WHERE rowid IN (SELECT rowId FROM messages WHERE senderId = $personIdExpr); " +
            "$INSERT $INDEX_ROWS WHERE m.senderId = $personIdExpr;"

    private const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE $TABLE USING fts5(" +
            "body, sender_name, attachment_names, " +
            "content='', contentless_delete=1, tokenize='unicode61 remove_diacritics 2')"

    val triggers: List<String> =
        listOf(
            "CREATE TRIGGER IF NOT EXISTS message_fts_ai AFTER INSERT ON messages BEGIN " +
                "$INSERT $INDEX_ROWS WHERE m.rowId = new.rowId; END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_ad AFTER DELETE ON messages BEGIN " +
                "DELETE FROM $TABLE WHERE rowid = old.rowId; END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_au AFTER UPDATE OF body, senderId ON messages BEGIN " +
                "DELETE FROM $TABLE WHERE rowid = old.rowId; $INSERT $INDEX_ROWS WHERE m.rowId = new.rowId; END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_attachment_ai AFTER INSERT ON attachments BEGIN " +
                "${reindexMessage("new.messageId")} END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_attachment_ad AFTER DELETE ON attachments BEGIN " +
                "${reindexMessage("old.messageId")} END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_attachment_au " +
                "AFTER UPDATE OF fileName, messageId ON attachments BEGIN " +
                "${reindexMessage("old.messageId")} ${reindexMessage("new.messageId")} END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_person_ai AFTER INSERT ON persons BEGIN " +
                "${reindexSender("new.id")} END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_person_au AFTER UPDATE OF displayName ON persons BEGIN " +
                "${reindexSender("new.id")} END",
            "CREATE TRIGGER IF NOT EXISTS message_fts_person_ad AFTER DELETE ON persons BEGIN " +
                "${reindexSender("old.id")} END",
        )

    /**
     * Creates the index and its triggers if missing. Runs on every open, so a database that
     * somehow lost the index (or predates it) gets it back, rebuilt from the messages.
     */
    fun ensure(connection: SQLiteConnection) {
        val exists =
            connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
                it.bindText(1, TABLE)
                it.step()
            }
        if (!exists) {
            connection.execSQL(CREATE_TABLE)
            connection.execSQL("$INSERT $INDEX_ROWS")
        }
        triggers.forEach(connection::execSQL)
    }

    /**
     * Turns what the user typed into a safe FTS5 query: every word becomes a quoted prefix
     * term ("sam"*), and all words must match. Returns null when there is nothing to search.
     */
    fun matchExpression(userText: String): String? =
        userText
            .split(Regex("\\s+"))
            .map { it.replace("\"", "") }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ") { "\"$it\"*" }
}
