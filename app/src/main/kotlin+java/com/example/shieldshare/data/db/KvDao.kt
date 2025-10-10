package com.example.shieldshare.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query

@Dao
interface KvDao {
    @Query("SELECT * FROM kv_store WHERE `key` = :key")
    suspend fun get(key: String): KvStore?

    @Delete
    suspend fun delete(kv: KvStore)

    @Query("DELETE FROM kv_store WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}