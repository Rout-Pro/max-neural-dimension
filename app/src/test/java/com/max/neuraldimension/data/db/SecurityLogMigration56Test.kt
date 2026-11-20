package com.max.neuraldimension.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free (Robolectric) evidence for the Part 19 security event history:
 * the MIGRATION_5_6 table creation and the SecurityDao record/read path.
 *
 * The v5 state of the database is rebuilt with the production migration
 * objects, then the production MIGRATION_5_6 runs. Asserts the new table
 * exists, is empty, and stores rows with its declared defaults. The DAO test
 * runs against the real v6 schema (fresh in-memory AppDb) and proves insert,
 * newest-first ordering and wipe. This proves migration and DAO logic only;
 * runtime migration on a real install is unproved (no device evidence).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityLogMigration56Test {

    private fun openV5Database(context: Context): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {}
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Rebuild v5 state via the production migration SQL.
                        MIGRATION_2_3.migrate(db) // notes + reminders
                        MIGRATION_3_4.migrate(db) // habits + expenses
                        MIGRATION_4_5.migrate(db) // reminders.deferredUntil
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    @Test
    fun migration56_creates_empty_security_events_table() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV5Database(context)
        MIGRATION_5_6.migrate(db)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='security_events'").use { c ->
            assertEquals("security_events table missing after migration", 1, c.count)
        }
        db.query("SELECT COUNT(*) FROM security_events").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // Rows can be written with only the required columns; detail defaults to ''.
        db.execSQL("INSERT INTO security_events (type, ts) VALUES ('pin_set', 1700000000000)")
        db.query("SELECT id, type, ts, detail FROM security_events").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
            assertEquals("pin_set", c.getString(1))
            assertEquals(1700000000000L, c.getLong(2))
            assertEquals("", c.getString(3))
        }
        db.close()
    }

    @Test
    fun migration56_preserves_existing_tables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV5Database(context)
        db.execSQL("INSERT INTO reminders (title, triggerAt, workId, fired) VALUES ('keep me', 1700000000000, 'w-9', 0)")
        MIGRATION_5_6.migrate(db)
        db.query("SELECT title FROM reminders").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("keep me", c.getString(0))
        }
        db.close()
    }

    @Test
    fun security_dao_inserts_orders_newest_first_and_clears() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDb.get(context)
        runBlocking(Dispatchers.IO) {
            db.securityDao().clear()
            db.securityDao().insert(SecurityEvent(type = "pin_set", ts = 1000))
            db.securityDao().insert(SecurityEvent(type = "pin_failed", ts = 3000))
            db.securityDao().insert(SecurityEvent(type = "pin_changed", ts = 2000))

            val events = db.securityDao().events().first()
            assertEquals(listOf("pin_failed", "pin_changed", "pin_set"), events.map { it.type })

            db.securityDao().clear()
            assertTrue(db.securityDao().events().first().isEmpty())
        }
    }
}
