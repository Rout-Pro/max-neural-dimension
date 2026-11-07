package com.max.neuraldimension.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Device-free (Robolectric) migration test for MIGRATION_4_5.
 *
 * The v4 state of the reminders table is rebuilt with the production migration
 * objects (MIGRATION_2_3 created it and nothing altered it through v4), two
 * rows are inserted, then the production MIGRATION_4_5 runs. Asserts rows, ids
 * and values are preserved and the new deferredUntil column defaults to 0.
 * This proves migration logic only; runtime migration on a real install is
 * unproved (no device evidence).
 */
@RunWith(RobolectricTestRunner::class)
class ReminderMigration45Test {

    private fun openV4Database(context: Context): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {}
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Rebuild v4 state via the production migration SQL.
                        MIGRATION_2_3.migrate(db) // creates notes + reminders
                        MIGRATION_3_4.migrate(db) // creates habits + expenses
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    @Test
    fun migration45_preserves_rows_ids_values_and_defaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV4Database(context)
        db.execSQL("INSERT INTO reminders (title, triggerAt, workId, fired) VALUES ('call mom', 1700000000000, 'w-1', 0)")
        db.execSQL("INSERT INTO reminders (title, triggerAt, workId, fired) VALUES ('standup', 1700003600000, 'w-2', 1)")

        MIGRATION_4_5.migrate(db)

        db.query("SELECT id, title, triggerAt, workId, fired, deferredUntil FROM reminders ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
            assertEquals("call mom", c.getString(1))
            assertEquals(1700000000000L, c.getLong(2))
            assertEquals("w-1", c.getString(3))
            assertEquals(0, c.getInt(4))
            assertEquals(0L, c.getLong(5))
            c.moveToNext()
            assertEquals(2L, c.getLong(0))
            assertEquals("standup", c.getString(1))
            assertEquals(1, c.getInt(4))
            assertEquals(0L, c.getLong(5))
        }
        db.close()
    }

    @Test
    fun migration45_is_idempotent_shape_on_empty_table() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV4Database(context)
        MIGRATION_4_5.migrate(db)
        db.query("SELECT COUNT(*) FROM reminders").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }
}
