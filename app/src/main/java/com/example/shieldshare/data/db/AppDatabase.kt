package com.example.shieldshare.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        KvStore::class,
        TrafficRecordEntity::class,
        ClientSessionEntity::class,
        ClientStatsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kvDao(): KvDao
    abstract fun trafficRecordDao(): TrafficRecordDao
    abstract fun clientSessionDao(): ClientSessionDao
    abstract fun clientStatsDao(): ClientStatsDao
}
