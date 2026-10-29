package com.riyadm.unsendvault.data.security

import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object SqlCipherSupport {
    @Volatile
    private var loaded = false

    /** Loads libsqlcipher.so once per process. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }

    /**
     * SQLCipher-backed open helper factory. SQLCipher keeps a reference to [passphrase] and re-keys every new pooled
     * connection (WAL uses several), so the array must stay intact while the database is open; zero it after close.
     *
     * `cipher_memory_security` is explicitly OFF: when ON, SQLCipher mlocks and wipes every allocation, which costs a
     * large amount of CPU on each query and can fail on devices with low RLIMIT_MEMLOCK. It only defends against an
     * attacker who can already read this process's memory — such an attacker could equally read the passphrase from the
     * Java heap or the decrypted rows in the UI — so it adds cost without changing our threat model (data at rest).
     */
    fun factory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        ensureLoaded()
        return SupportOpenHelperFactory(passphrase, Hook, /* enableWriteAheadLogging = */ true)
    }

    private object Hook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) {
            try {
                connection.executeRaw("PRAGMA cipher_memory_security = OFF", null, null)
            } catch (e: Exception) {
                Log.w("UvSqlCipher", "cipher_memory_security pragma not applied", e)
            }
        }

        override fun postKey(connection: SQLiteConnection) = Unit
    }
}
