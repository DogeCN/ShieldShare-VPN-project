package com.example.shieldshare.managers.encryption

import com.example.shieldshare.managers.meter.TrafficStats

/** Provides encryption services for data protection and secure transmission */
interface IEncryptionService {
    fun encryptForFirebase(data: TrafficStats): EncryptedPayload
    fun decryptFromFirebase(payload: EncryptedPayload): TrafficStats
    fun encryptDatabase(passphrase: ByteArray)
}

data class EncryptedPayload(
        val encryptedData: String,
        val iv: String,
        val encryptedKey: String,
        val timestamp: Long = System.currentTimeMillis()
)
