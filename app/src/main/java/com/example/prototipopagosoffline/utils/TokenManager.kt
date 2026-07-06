package com.example.prototipopagosoffline.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String) {
        sharedPreferences.edit().putString("jwt_token", token).apply()
    }

    fun getToken(): String? {
        return sharedPreferences.getString("jwt_token", null)
    }

    fun clearToken() {
        sharedPreferences.edit().remove("jwt_token").apply()
    }

    fun getDatabaseKey(): ByteArray {
        val key = sharedPreferences.getString("db_secret_key", null)
        if (key != null) {
            return android.util.Base64.decode(key, android.util.Base64.DEFAULT)
        }
        val newKey = java.security.SecureRandom().generateSeed(32)
        sharedPreferences.edit().putString("db_secret_key", android.util.Base64.encodeToString(newKey, android.util.Base64.DEFAULT)).apply()
        return newKey
    }
}
