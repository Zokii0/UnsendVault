package com.riyadm.unsendvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Archive database. Schema history (exported to app/schemas):
 *  - v1: conversations, messages, message_revisions.
 *  - v2: messages.last_notification_key / last_removal_reason / last_removed_at (+ index) and the external-content
 *        FTS4 table messages_fts. See [Migrations.MIGRATION_1_2].
 *
 * Never uses destructive fallback migrations: losing the archive silently is worse than a visible error.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageRevisionEntity::class,
        MessageFtsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        const val FILE_NAME = "unsendvault.db"

        /**
         * @param name database file name, or null for an in-memory database (tests).
         * @param factory open-helper factory; production passes SQLCipher's `SupportOpenHelperFactory`. null = the
         *   framework SQLite helper, used by Robolectric tests because SQLCipher's native library can't load on the JVM.
         */
        fun build(
            context: Context,
            name: String?,
            factory: SupportSQLiteOpenHelper.Factory?,
            allowMainThreadQueries: Boolean = false,
        ): VaultDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, VaultDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, name)
                    // WAL lets the listener write while the UI reads. SQLCipher encrypts WAL pages as well.
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            }
            factory?.let(builder::openHelperFactory)
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            return builder.addMigrations(*Migrations.ALL).build()
        }
    }
}
