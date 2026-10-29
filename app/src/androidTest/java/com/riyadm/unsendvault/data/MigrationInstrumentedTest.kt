package com.riyadm.unsendvault.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.data.db.Migration2To3
import com.riyadm.unsendvault.data.db.Migrations
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.security.FieldCipher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room MigrationTestHelper against the exported schemas (app/schemas is part of androidTest assets), on the device's
 * framework SQLite. 2→3 is the cipher-aware migration: it is constructed with the vault's [FieldCipher].
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    private val cipher = FieldCipher.random()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2() {
        val name = "uv-migration-1-2-test.db"
        helper.createDatabase(name, 1).use { MigrationFixtures.insertV1(it) }
        helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).use { MigrationFixtures.assertV2(it) }
    }

    @Test
    fun migrate1To2To3_encryptsAndValidates() {
        val name = "uv-migration-1-3-test.db"
        helper.createDatabase(name, 1).use { MigrationFixtures.insertV1(it) }
        helper.runMigrationsAndValidate(name, 3, true, Migrations.MIGRATION_1_2, Migration2To3(cipher)).use {
            MigrationFixtures.assertV3(it, cipher, fromV2 = false)
        }
    }

    @Test
    fun migrate2To3_encryptsAndValidates() {
        val name = "uv-migration-2-3-test.db"
        helper.createDatabase(name, 2).use { MigrationFixtures.insertV2(it) }
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3(cipher)).use {
            MigrationFixtures.assertV3(it, cipher, fromV2 = true)
        }
    }
}
