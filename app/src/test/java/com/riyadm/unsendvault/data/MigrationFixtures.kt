package com.riyadm.unsendvault.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import com.riyadm.unsendvault.data.security.FieldCipher.IndexDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Raw-SQL v1/v2 rows and v2/v3 assertions shared by the JVM and instrumented migration tests (keep in sync). */
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

    const val NOTIFICATION_KEY = "0|com.example.chat|1|null|10001"

    /** v2 database: the v1 rows plus removal columns on one message (FTS content is rebuilt by the caller). */
    fun insertV2(db: SupportSQLiteDatabase) {
        insertV1(db)
        db.execSQL("UPDATE messages SET last_notification_key = '$NOTIFICATION_KEY', last_removal_reason = 'USER_DISMISSED', last_removed_at = 2500 WHERE id = 10")
        db.execSQL("INSERT INTO `messages_fts`(`messages_fts`) VALUES ('rebuild')")
    }

    /**
     * v3 content after migrating the v1/v2 fixture: every content column is a ciphertext that decrypts (with the
     * migration's [cipher]) to the original value, lookup columns hold blind indexes of the old lookup values, FTS is
     * gone and the key check is stored. [fromV2] = the removal columns set by [insertV2] are expected.
     */
    fun assertV3(db: SupportSQLiteDatabase, cipher: FieldCipher, fromV2: Boolean) {
        db.query("SELECT conversation_index, conversation_key, title, last_message_preview, typeof(title) FROM conversations WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(cipher.blindIndex("c1", IndexDomain.CONVERSATION), c.getString(0))
            val owner = FieldCipher.forConversation("com.example.chat", c.getString(0))
            assertEquals("c1", cipher.decryptOrNull(c.getBlob(1), Field.CONVERSATION_KEY, owner))
            assertEquals("Bob", cipher.decryptOrNull(c.getBlob(2), Field.CONVERSATION_TITLE, owner))
            assertEquals("Hello migrated world", cipher.decryptOrNull(c.getBlob(3), Field.CONVERSATION_PREVIEW, owner))
            assertEquals("blob", c.getString(4))
        }
        db.query(
            "SELECT id, sender, sender_index, text, text_index, is_favorite, status, deletion_marker_text, " +
                "last_notification_index, last_removal_reason, last_removed_at, typeof(text) FROM messages ORDER BY id",
        ).use { c ->
            assertEquals(2, c.count)
            val owner = FieldCipher.forMessage(1)
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
            assertEquals("Bob", cipher.decryptOrNull(c.getBlob(1), Field.MESSAGE_SENDER, owner))
            assertEquals(cipher.blindIndex("Bob", IndexDomain.SENDER), c.getString(2))
            assertEquals("Hello migrated world", cipher.decryptOrNull(c.getBlob(3), Field.MESSAGE_TEXT, owner))
            assertEquals(cipher.blindIndex("Hello migrated world", IndexDomain.TEXT), c.getString(4))
            assertEquals(1, c.getInt(5))
            assertEquals("blob", c.getString(11))
            if (fromV2) {
                assertEquals(cipher.blindIndex(NOTIFICATION_KEY, IndexDomain.NOTIFICATION), c.getString(8))
                assertEquals("USER_DISMISSED", c.getString(9))
                assertEquals(2500L, c.getLong(10))
            } else {
                assertTrue(c.isNull(8) && c.isNull(9) && c.isNull(10))
            }
            c.moveToNext()
            assertTrue(c.isNull(1))
            assertEquals(cipher.blindIndex("", IndexDomain.SENDER), c.getString(2))
            assertEquals("second", cipher.decryptOrNull(c.getBlob(3), Field.MESSAGE_TEXT, owner))
            assertEquals("POSSIBLY_DELETED", c.getString(6))
            assertEquals("deleted", cipher.decryptOrNull(c.getBlob(7), Field.MESSAGE_MARKER, owner))
        }
        db.query("SELECT message_id, text FROM message_revisions").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Hello world", cipher.decryptOrNull(c.getBlob(1), Field.REVISION_TEXT, FieldCipher.forRevision(c.getLong(0))))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%fts%' OR name LIKE '%_v2'").use { c ->
            c.moveToFirst()
            assertEquals("FTS table/triggers and v2 tables are gone", 0, c.getInt(0))
        }
        db.query("SELECT value FROM vault_meta WHERE `key` = 'key_check'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(cipher.verifyKeyCheck(c.getBlob(0)))
            assertFalse(FieldCipher.random().verifyKeyCheck(c.getBlob(0)))
        }
        // No plaintext column survived anywhere in the content tables.
        for ((table, columns) in mapOf("messages" to listOf("text", "sender", "deletion_marker_text"), "conversations" to listOf("title", "last_message_preview"), "message_revisions" to listOf("text"))) {
            columns.forEach { col ->
                db.query("SELECT COUNT(*) FROM `$table` WHERE typeof(`$col`) = 'text'").use { c ->
                    c.moveToFirst()
                    assertEquals("$table.$col", 0, c.getInt(0))
                }
            }
        }
    }
}
