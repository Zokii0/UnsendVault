package com.riyadm.unsendvault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM run of the 1→2 migration. Robolectric can't expose test assets to MigrationTestHelper, so the v1 database is
 * created from the exported app/schemas/…/1.json (tables, indices, Room identity hash), then opened by Room at v2 with
 * [com.riyadm.unsendvault.data.db.Migrations.MIGRATION_1_2]; Room validates the migrated schema against v2 on open
 * (throws "Migration didn't properly handle …" on any mismatch). The instrumented twin uses MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val dbName = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun schemaFile(version: Int): File {
        val rel = "schemas/com.riyadm.unsendvault.data.db.VaultDatabase/$version.json"
        return listOf(File(rel), File("app/$rel")).first { it.exists() }
    }

    private fun createV1() {
        val db = Json.parseToJsonElement(schemaFile(1).readText()).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            sql.execSQL("PRAGMA foreign_keys = ON")
            for (entity in db.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                sql.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { idx ->
                    sql.execSQL(idx.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.getValue("setupQueries").jsonArray.forEach { sql.execSQL(it.jsonPrimitive.content) }
            sql.version = 1
        }
        // Insert fixtures through the support API so the shared fixture code is used.
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = error("must exist")
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("no upgrade")
                }).build(),
        )
        MigrationFixtures.insertV1(helper.writableDatabase)
        helper.close()
    }

    @Test
    fun migrate1To2_preservesData_validatesSchema_andIndexesForSearch() {
        createV1()
        val room = VaultDatabase.build(context, dbName, null, allowMainThreadQueries = true)
        try {
            // Opening triggers MIGRATION_1_2 + Room's schema validation.
            val raw = room.openHelper.writableDatabase
            assertEquals(2, raw.version)
            MigrationFixtures.assertV2(raw)
            val repo = RoomVaultRepository(room)
            runBlocking {
                val hits = repo.observeMessages(MessageQuery(text = "migrated")).first()
                assertEquals(listOf("Hello migrated world"), hits.map { it.text })
                assertNull(hits.single().lastRemovalReason)
                val detail = repo.observeMessage(hits.single().id).first()!!
                assertEquals(2, detail.message.revisionCount)
                assertEquals(listOf("Hello world"), detail.revisions.map { it.text })
                // Sender (FTS) and conversation title "Bob" (LIKE) both match.
                assertEquals(listOf("Bob", null), repo.observeMessages(MessageQuery(text = "bob")).first().map { it.sender })
                // New writes after migration work end to end.
                repo.ingest(Fx.parsed(Fx.msg("post migration words", key = "c1", sender = "Bob")), Fx.T0)
                assertEquals(1, repo.observeMessages(MessageQuery(text = "post migration")).first().size)
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
