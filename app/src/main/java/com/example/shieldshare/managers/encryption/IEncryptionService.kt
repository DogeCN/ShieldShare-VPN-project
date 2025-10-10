package com.example.shieldshare.managers.encryption

import com.example.shieldshare.managers.meter.TrafficStats

/**
 * Encryption Service Interface
 * Based on the CSV specification (IEncryptionService)
 */
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
