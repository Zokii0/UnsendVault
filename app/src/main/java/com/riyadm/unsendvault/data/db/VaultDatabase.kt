package com.riyadm.unsendvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.riyadm.unsendvault.data.security.FieldCipher

/**
 * Archive database on the framework SQLite (androidx.sqlite). Schema history (exported to app/schemas):
 *  - v1: conversations, messages, message_revisions.
 *  - v2: messages.last_notification_key / last_removal_reason / last_removed_at (+ index) and the external-content
 *        FTS4 table messages_fts. See [Migrations.MIGRATION_1_2].
 *  - v3: field-level encryption. Content columns are AES-GCM BLOBs, lookup columns are blind indexes, messages_fts is
 *        gone (search decrypts and filters in Kotlin), vault_meta holds the key check. See [Migration2To3].
 *
 * Never uses destructive fallback migrations: losing the archive silently is worse than a visible error.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageRevisionEntity::class,
        VaultMetaEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    /** Field cipher whose keys this database's encrypted columns use. Set once by [build]. */
    lateinit var cipher: FieldCipher
        private set

    companion object {
        /** v3+ vault. A new name so a 1.0.15 SQLCipher file is never handed to the framework SQLite by mistake. */
        const val FILE_NAME = "vault-v3.db"

        /** File name used up to 1.0.15 (SQLCipher). See [com.riyadm.unsendvault.data.security.LegacyArchive]. */
        const val LEGACY_FILE_NAME = "unsendvault.db"

        /**
         * @param name database file name, or null for an in-memory database (tests).
         * @param cipher encrypts/decrypts the content columns; also used by the 2→3 migration.
         */
        fun build(
            context: Context,
            name: String?,
            cipher: FieldCipher,
            allowMainThreadQueries: Boolean = false,
        ): VaultDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, VaultDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, name)
                    // WAL lets the listener write while the UI reads.
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            }
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            return builder.addMigrations(*Migrations.all(cipher)).build().also { it.cipher = cipher }
        }
    }
}

/**
 * Checks that this database's content was encrypted with [VaultDatabase.cipher]'s key: verifies the key check stored in
 * vault_meta, or stores one on a new vault. False means a different key (the archive cannot be decrypted).
 */
suspend fun VaultDatabase.verifyOrInitKeyCheck(): Boolean {
    val dao = dao()
    val stored = dao.metaValue(Migration2To3.KEY_CHECK)
    if (stored == null) {
        dao.putMeta(Migration2To3.KEY_CHECK, cipher.keyCheckBlob())
        return true
    }
    return cipher.verifyKeyCheck(stored)
}
