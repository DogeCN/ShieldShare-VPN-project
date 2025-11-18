package com.example.shieldshare.data.prefs

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import javax.crypto.AEADBadTagException

class AppPrefs(context: Context) {
    companion object {
        private const val TAG = "AppPrefs"
        private const val PREFS_NAME = "shieldshare_prefs"
    }

    private val masterKey =
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private val sp =
            try {
                EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: AEADBadTagException) {
                // Encryption key changed or data corrupted - delete old file and recreate
                Log.w(
                        TAG,
                        "EncryptedSharedPreferences decryption failed, clearing corrupted data",
                        e
                )
                try {
                    val prefsFile = File(context.filesDir.parent, "shared_prefs/$PREFS_NAME.xml")
                    if (prefsFile.exists()) {
                        prefsFile.delete()
                        Log.i(TAG, "Deleted corrupted preferences file")
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to delete corrupted preferences file", ex)
                }
                // Retry creating encrypted preferences
                EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(
                        TAG,
                        "Failed to create EncryptedSharedPreferences, falling back to regular SharedPreferences",
                        e
                )
                // Fallback to regular SharedPreferences if encryption completely fails
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }

    fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }
    fun getString(key: String, def: String? = null): String? = sp.getString(key, def)

    fun putInt(key: String, value: Int) {
        sp.edit().putInt(key, value).apply()
    }
    fun getInt(key: String, def: Int = 0): Int = sp.getInt(key, def)

    fun putLong(key: String, value: Long) {
        sp.edit().putLong(key, value).apply()
    }
    fun getLong(key: String, def: Long = 0): Long = sp.getLong(key, def)

    fun putFloat(key: String, value: Float) {
        sp.edit().putFloat(key, value).apply()
    }
    fun getFloat(key: String, def: Float = 0f): Float = sp.getFloat(key, def)

    fun putBoolean(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }
    fun getBoolean(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)
}
