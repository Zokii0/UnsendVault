package com.riyadm.unsendvault.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.data.db.Migrations
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.security.SqlCipherSupport
import com.riyadm.unsendvault.data.security.VaultKeyManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** Room MigrationTestHelper 1→2 against the exported schemas (app/schemas is part of androidTest assets). */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_frameworkSqlite() {
        val name = "uv-migration-framework-test.db"
        helper.createDatabase(name, 1).use { MigrationFixtures.insertV1(it) }
        helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).use { MigrationFixtures.assertV2(it) }
    }
}

/** Same migration on SQLCipher's SQLite build (FTS4 + unicode61 must be available there too). */
@RunWith(AndroidJUnit4::class)
class SqlCipherMigrationInstrumentedTest {
    private val passphrase = VaultKeyManager.toSqlCipherRawKey(ByteArray(32).also(SecureRandom()::nextBytes))

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
        emptyList(),
        SqlCipherSupport.factory(passphrase),
    )

    @Test
    fun migrate1To2_sqlcipher() {
        val name = "uv-migration-sqlcipher-test.db"
        helper.createDatabase(name, 1).use { MigrationFixtures.insertV1(it) }
        helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).use { MigrationFixtures.assertV2(it) }
    }
}
