package com.example.shieldshare.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private val sp = EncryptedSharedPreferences.create(
        context,
        "shieldshare_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
    fun getString(key: String, def: String? = null): String? = sp.getString(key, def)
    
    fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    fun getInt(key: String, def: Int = 0): Int = sp.getInt(key, def)
    
    fun putBoolean(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    fun getBoolean(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)
}
