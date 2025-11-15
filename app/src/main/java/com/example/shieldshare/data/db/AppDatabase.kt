package com.example.shieldshare.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        KvStore::class,
        TrafficRecordEntity::class,
        ClientSessionEntity::class,
        ClientStatsEntity::class,
        ServiceSessionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kvDao(): KvDao
    abstract fun trafficRecordDao(): TrafficRecordDao
    abstract fun clientSessionDao(): ClientSessionDao
    abstract fun clientStatsDao(): ClientStatsDao
    abstract fun serviceSessionDao(): ServiceSessionDao
}
