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

    fun saveSession(token: String, walletId: String, userId: Int) {
        sharedPreferences.edit()
            .putString("jwt_token", token)
            .putString("wallet_id", walletId)
            .putInt("user_id", userId)
            .commit()
    }

    fun saveToken(token: String) {
        sharedPreferences.edit().putString("jwt_token", token).apply()
    }

    fun getToken(): String? {
        return sharedPreferences.getString("jwt_token", null)
    }

    fun getWalletId(): String? = sharedPreferences.getString("wallet_id", null)?.takeIf { it.isNotBlank() }

    fun getUserId(): Int? = sharedPreferences.getInt("user_id", -1).takeIf { it > 0 }

    fun clearToken() {
        sharedPreferences.edit().clear().apply()
    }
}
