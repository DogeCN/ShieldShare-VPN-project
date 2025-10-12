package com.example.shieldshare.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [KvStore::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kvDao(): KvDao
}
