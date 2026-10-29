package com.riyadm.unsendvault.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Raw-SQL v1 rows and v2 assertions shared by the JVM and instrumented migration tests (keep in sync). */
object MigrationFixtures {
    fun insertV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversations (id, package_name, app_label, conversation_key, title, is_group, created_at, " +
                "last_message_at, last_message_preview, message_count) VALUES (1, 'com.example.chat', 'Chat', 'c1', 'Bob', 0, 1, 2000, 'Hello migrated world', 2)",
        )
        db.execSQL(
            "INSERT INTO messages (id, conversation_id, package_name, sender, sender_key, text, normalized_text, content_kind, " +
                "message_timestamp, has_precise_timestamp, first_captured_at, last_updated_at, is_favorite, status, " +
                "deletion_adapter_id, deletion_marker_text, deletion_observed_at, revision_count) VALUES " +
                "(10, 1, 'com.example.chat', 'Bob', 'Bob', 'Hello migrated world', 'Hello migrated world', 'TEXT', 2000, 1, 1500, 1600, 1, 'ACTIVE', NULL, NULL, NULL, 2)",
        )
        db.execSQL(
            "INSERT INTO messages (id, conversation_id, package_name, sender, sender_key, text, normalized_text, content_kind, " +
                "message_timestamp, has_precise_timestamp, first_captured_at, last_updated_at, is_favorite, status, " +
                "deletion_adapter_id, deletion_marker_text, deletion_observed_at, revision_count) VALUES " +
                "(11, 1, 'com.example.chat', NULL, '', 'second', 'second', 'TEXT', 1000, 0, 1000, 1000, 0, 'POSSIBLY_DELETED', 'x', 'deleted', 1700, 1)",
        )
        db.execSQL("INSERT INTO message_revisions (id, message_id, text, content_kind, captured_at) VALUES (100, 10, 'Hello world', 'TEXT', 1500)")
    }

    fun assertV2(db: SupportSQLiteDatabase) {
        db.query("SELECT id, text, is_favorite, status, deletion_marker_text, last_notification_key, last_removal_reason, last_removed_at FROM messages ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
            assertEquals("Hello migrated world", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertTrue(c.isNull(5) && c.isNull(6) && c.isNull(7))
            c.moveToNext()
            assertEquals("POSSIBLY_DELETED", c.getString(3))
            assertEquals("deleted", c.getString(4))
        }
        db.query("SELECT COUNT(*) FROM message_revisions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        // Existing rows were indexed by the 'rebuild'.
        db.query("SELECT docid FROM messages_fts WHERE messages_fts MATCH 'migr*'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
        }
        // Triggers keep FTS in sync for new writes.
        db.execSQL("UPDATE messages SET text = 'brand new words' WHERE id = 11")
        db.query("SELECT docid FROM messages_fts WHERE messages_fts MATCH 'brand'").use { c -> assertEquals(1, c.count) }
        db.query("SELECT docid FROM messages_fts WHERE messages_fts MATCH 'second'").use { c -> assertEquals(0, c.count) }
        db.execSQL("UPDATE messages SET text = 'second' WHERE id = 11")
    }
}
