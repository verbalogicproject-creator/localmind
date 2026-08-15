package com.example.demo

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Item::class],
    version = 2,
    exportSchema = true,
)
abstract class DemoDatabase : RoomDatabase()
