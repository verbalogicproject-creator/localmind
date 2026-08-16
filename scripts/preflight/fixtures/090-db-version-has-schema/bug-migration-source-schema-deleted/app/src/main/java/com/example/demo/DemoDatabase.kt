package com.example.demo

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Three migrations, so MigrationTestHelper needs schemas 1, 2 and 3 as well as 4.
// Only 4.json is committed below -- which is exactly what a local KSP run leaves
// behind if the schemas directory is ever cleared and "regenerated".
@Database(
    entities = [Item::class],
    version = 4,
    exportSchema = true,
)
abstract class DemoDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
    }
}
