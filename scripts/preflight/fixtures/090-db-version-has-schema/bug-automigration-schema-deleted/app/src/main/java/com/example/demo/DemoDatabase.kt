package com.example.demo

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

// Room generates this migration, but it still reads BOTH schemas to do it.
@Database(
    entities = [Item::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class DemoDatabase : RoomDatabase()
