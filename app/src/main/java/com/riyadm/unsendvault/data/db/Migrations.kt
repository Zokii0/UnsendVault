package com.riyadm.unsendvault.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import com.riyadm.unsendvault.data.security.FieldCipher.IndexDomain

/**
 * Hand-written migrations. Each must reproduce the exported schema of its target version exactly (Room validates
 * tables/indices on open; MigrationTestHelper checks it in tests).
 */
object Migrations {

    /**
     * v1 → v2:
     *  1. Removal transparency columns on `messages` (nullable, so existing rows need no backfill) + lookup index.
     *  2. External-content FTS4 table `messages_fts` over messages(text, sender), the four content-sync triggers Room
     *     would create for a fresh v2 database, and a 'rebuild' to index every existing message.
     * Everything runs inside the single transaction Room opens for a migration.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `last_notification_key` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `last_removal_reason` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `last_removed_at` INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_last_notification_key` ON `messages` (`last_notification_key`)",
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(" +
                    "`text` TEXT NOT NULL, `sender` TEXT, tokenize=unicode61, content=`messages`)",
            )
            FTS_TRIGGERS.forEach(db::execSQL)
            db.execSQL("INSERT INTO `messages_fts`(`messages_fts`) VALUES ('rebuild')")
        }
    }

    /** Identical to the contentSyncTriggers Room exported for the v2 FTS entity (2.json). */
    internal val FTS_TRIGGERS = listOf(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` " +
            "BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` " +
            "BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` " +
            "BEGIN INSERT INTO `messages_fts`(`docid`, `text`, `sender`) VALUES (NEW.`rowid`, NEW.`text`, NEW.`sender`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` " +
            "BEGIN INSERT INTO `messages_fts`(`docid`, `text`, `sender`) VALUES (NEW.`rowid`, NEW.`text`, NEW.`sender`); END",
    )

    /** Every migration; 2→3 needs the vault's [FieldCipher] to encrypt existing rows. */
    fun all(cipher: FieldCipher): Array<Migration> = arrayOf(MIGRATION_1_2, Migration2To3(cipher))
}

/**
 * v2 → v3: field-level encryption (create-new, copy, drop, rename-free swap). In the single migration transaction:
 *  1. Drop the FTS4 table `messages_fts` and its four content-sync triggers (search no longer uses an index).
 *  2. Rename the v2 tables to `*_v2`, create the v3 tables under the real names.
 *  3. Copy every row, keeping ids: content columns encrypted with [cipher] (AAD bound to column + owner), lookup
 *     columns replaced by blind indexes of the *stored* v2 values (normalized_text, sender_key, conversation_key,
 *     last_notification_key) so identity matching sees exactly the same equalities as before.
 *  4. Drop the v2 tables (their indices go with them), create the v3 indices, and store the key check in vault_meta.
 * Room validates the result against the exported v3 schema before the database is used.
 *
 * Note: this migrates a plaintext (framework SQLite) v2 database. Release 1.0.15's SQLCipher files can't be read
 * without SQLCipher and are handled by [com.riyadm.unsendvault.data.security.LegacyArchive] instead.
 */
class Migration2To3(private val cipher: FieldCipher) : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        FTS_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
        db.execSQL("DROP TABLE IF EXISTS `messages_fts`")

        db.execSQL("ALTER TABLE `message_revisions` RENAME TO `message_revisions_v2`")
        db.execSQL("ALTER TABLE `messages` RENAME TO `messages_v2`")
        db.execSQL("ALTER TABLE `conversations` RENAME TO `conversations_v2`")
        CREATE_TABLES.forEach(db::execSQL)

        copyConversations(db)
        copyMessages(db)
        copyRevisions(db)

        db.execSQL("DROP TABLE `message_revisions_v2`")
        db.execSQL("DROP TABLE `messages_v2`")
        db.execSQL("DROP TABLE `conversations_v2`")
        CREATE_INDICES.forEach(db::execSQL)

        db.insert(
            "vault_meta",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("key", KEY_CHECK)
                put("value", cipher.keyCheckBlob())
            },
        )
    }

    private fun copyConversations(db: SupportSQLiteDatabase) {
        val insert = db.compileStatement(
            "INSERT INTO `conversations` (`id`, `package_name`, `app_label`, `conversation_index`, `conversation_key`, " +
                "`title`, `is_group`, `created_at`, `last_message_at`, `last_message_preview`, `message_count`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        db.query(
            "SELECT `id`, `package_name`, `app_label`, `conversation_key`, `title`, `is_group`, `created_at`, " +
                "`last_message_at`, `last_message_preview`, `message_count` FROM `conversations_v2` ORDER BY `id`",
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                val key = c.getString(3)
                val index = cipher.blindIndex(key, IndexDomain.CONVERSATION)
                val owner = FieldCipher.forConversation(pkg, index)
                insert.clearBindings()
                insert.bindLong(1, c.getLong(0))
                insert.bindString(2, pkg)
                insert.bindString(3, c.getString(2))
                insert.bindString(4, index)
                insert.bindBlob(5, cipher.encrypt(key, Field.CONVERSATION_KEY, owner))
                insert.bindBlob(6, cipher.encrypt(c.getString(4), Field.CONVERSATION_TITLE, owner))
                insert.bindLong(7, c.getLong(5))
                insert.bindLong(8, c.getLong(6))
                insert.bindLong(9, c.getLong(7))
                if (c.isNull(8)) insert.bindNull(10) else insert.bindBlob(10, cipher.encrypt(c.getString(8), Field.CONVERSATION_PREVIEW, owner))
                insert.bindLong(11, c.getLong(9))
                insert.executeInsert()
            }
        }
        insert.close()
    }

    private fun copyMessages(db: SupportSQLiteDatabase) {
        val insert = db.compileStatement(
            "INSERT INTO `messages` (`id`, `conversation_id`, `package_name`, `sender`, `sender_index`, `text`, " +
                "`text_index`, `content_kind`, `message_timestamp`, `has_precise_timestamp`, `first_captured_at`, " +
                "`last_updated_at`, `is_favorite`, `status`, `deletion_adapter_id`, `deletion_marker_text`, " +
                "`deletion_observed_at`, `revision_count`, `last_notification_index`, `last_removal_reason`, " +
                "`last_removed_at`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        db.query(
            "SELECT `id`, `conversation_id`, `package_name`, `sender`, `sender_key`, `text`, `normalized_text`, " +
                "`content_kind`, `message_timestamp`, `has_precise_timestamp`, `first_captured_at`, `last_updated_at`, " +
                "`is_favorite`, `status`, `deletion_adapter_id`, `deletion_marker_text`, `deletion_observed_at`, " +
                "`revision_count`, `last_notification_key`, `last_removal_reason`, `last_removed_at` " +
                "FROM `messages_v2` ORDER BY `id`",
        ).use { c ->
            while (c.moveToNext()) {
                val owner = FieldCipher.forMessage(c.getLong(1))
                insert.clearBindings()
                insert.bindLong(1, c.getLong(0))
                insert.bindLong(2, c.getLong(1))
                insert.bindString(3, c.getString(2))
                if (c.isNull(3)) insert.bindNull(4) else insert.bindBlob(4, cipher.encrypt(c.getString(3), Field.MESSAGE_SENDER, owner))
                insert.bindString(5, cipher.blindIndex(c.getString(4), IndexDomain.SENDER))
                insert.bindBlob(6, cipher.encrypt(c.getString(5), Field.MESSAGE_TEXT, owner))
                insert.bindString(7, cipher.blindIndex(c.getString(6), IndexDomain.TEXT))
                insert.bindString(8, c.getString(7))
                for (col in 8..12) insert.bindLong(col + 1, c.getLong(col))
                insert.bindString(14, c.getString(13))
                if (c.isNull(14)) insert.bindNull(15) else insert.bindString(15, c.getString(14))
                if (c.isNull(15)) insert.bindNull(16) else insert.bindBlob(16, cipher.encrypt(c.getString(15), Field.MESSAGE_MARKER, owner))
                if (c.isNull(16)) insert.bindNull(17) else insert.bindLong(17, c.getLong(16))
                insert.bindLong(18, c.getLong(17))
                if (c.isNull(18)) insert.bindNull(19) else insert.bindString(19, cipher.blindIndex(c.getString(18), IndexDomain.NOTIFICATION))
                if (c.isNull(19)) insert.bindNull(20) else insert.bindString(20, c.getString(19))
                if (c.isNull(20)) insert.bindNull(21) else insert.bindLong(21, c.getLong(20))
                insert.executeInsert()
            }
        }
        insert.close()
    }

    private fun copyRevisions(db: SupportSQLiteDatabase) {
        val insert = db.compileStatement(
            "INSERT INTO `message_revisions` (`id`, `message_id`, `text`, `content_kind`, `captured_at`) VALUES (?, ?, ?, ?, ?)",
        )
        db.query("SELECT `id`, `message_id`, `text`, `content_kind`, `captured_at` FROM `message_revisions_v2` ORDER BY `id`").use { c ->
            while (c.moveToNext()) {
                val messageId = c.getLong(1)
                insert.clearBindings()
                insert.bindLong(1, c.getLong(0))
                insert.bindLong(2, messageId)
                insert.bindBlob(3, cipher.encrypt(c.getString(2), Field.REVISION_TEXT, FieldCipher.forRevision(messageId)))
                insert.bindString(4, c.getString(3))
                insert.bindLong(5, c.getLong(4))
                insert.executeInsert()
            }
        }
        insert.close()
    }

    companion object {
        const val KEY_CHECK = "key_check"

        private val FTS_TRIGGER_NAMES = listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")
            .map { "room_fts_content_sync_messages_fts_$it" }

        /** Identical to the createSql of the v3 entities in app/schemas/…/3.json. */
        internal val CREATE_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`package_name` TEXT NOT NULL, `app_label` TEXT NOT NULL, `conversation_index` TEXT NOT NULL, " +
                "`conversation_key` BLOB NOT NULL, `title` BLOB NOT NULL, `is_group` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `last_message_at` INTEGER NOT NULL, `last_message_preview` BLOB, " +
                "`message_count` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`conversation_id` INTEGER NOT NULL, `package_name` TEXT NOT NULL, `sender` BLOB, " +
                "`sender_index` TEXT NOT NULL, `text` BLOB NOT NULL, `text_index` TEXT NOT NULL, " +
                "`content_kind` TEXT NOT NULL, `message_timestamp` INTEGER NOT NULL, " +
                "`has_precise_timestamp` INTEGER NOT NULL, `first_captured_at` INTEGER NOT NULL, " +
                "`last_updated_at` INTEGER NOT NULL, `is_favorite` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`deletion_adapter_id` TEXT, `deletion_marker_text` BLOB, `deletion_observed_at` INTEGER, " +
                "`revision_count` INTEGER NOT NULL, `last_notification_index` TEXT, `last_removal_reason` TEXT, " +
                "`last_removed_at` INTEGER, FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `message_revisions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`message_id` INTEGER NOT NULL, `text` BLOB NOT NULL, `content_kind` TEXT NOT NULL, " +
                "`captured_at` INTEGER NOT NULL, FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `vault_meta` (`key` TEXT NOT NULL, `value` BLOB NOT NULL, PRIMARY KEY(`key`))",
        )

        internal val CREATE_INDICES = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_package_name_conversation_index` " +
                "ON `conversations` (`package_name`, `conversation_index`)",
            "CREATE INDEX IF NOT EXISTS `index_conversations_last_message_at` ON `conversations` (`last_message_at`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_message_timestamp` " +
                "ON `messages` (`conversation_id`, `message_timestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_package_name_message_timestamp` " +
                "ON `messages` (`package_name`, `message_timestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_message_timestamp` ON `messages` (`message_timestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_is_favorite` ON `messages` (`is_favorite`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_status` ON `messages` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_sender_index_message_timestamp` " +
                "ON `messages` (`conversation_id`, `sender_index`, `message_timestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_sender_index_text_index` " +
                "ON `messages` (`conversation_id`, `sender_index`, `text_index`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_last_notification_index` ON `messages` (`last_notification_index`)",
            "CREATE INDEX IF NOT EXISTS `index_message_revisions_message_id` ON `message_revisions` (`message_id`)",
        )
    }
}
