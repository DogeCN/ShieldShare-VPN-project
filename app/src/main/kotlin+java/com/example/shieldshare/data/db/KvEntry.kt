package com.example.shieldshare.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kv_store")
data class KvStore(
    @PrimaryKey
    val key: String,
    val value: String
)
