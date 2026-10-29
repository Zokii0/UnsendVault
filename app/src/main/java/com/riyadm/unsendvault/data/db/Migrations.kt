package com.riyadm.unsendvault.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    /** Identical to the contentSyncTriggers Room exports for [MessageFtsEntity] in 2.json. */
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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
