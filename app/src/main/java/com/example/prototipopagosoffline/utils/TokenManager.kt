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

    fun saveSession(token: String, walletId: String, userId: Int): Boolean =
        sharedPreferences.edit()
            .putString("jwt_token", token)
            .putString("wallet_id", walletId)
            .putInt("user_id", userId)
            .putString("session_role", "cliente")
            .remove("terminal_id")
            .commit()

    fun saveMerchantSession(token: String, terminalId: String, userId: Int): Boolean =
        sharedPreferences.edit()
            .putString("jwt_token", token)
            .putString("terminal_id", terminalId)
            .putInt("user_id", userId)
            .putString("session_role", "comercio")
            .remove("wallet_id")
            .commit()

    fun saveToken(token: String) {
        check(sharedPreferences.edit().putString("jwt_token", token).commit()) {
            "No se pudo guardar el token"
        }
    }

    fun getToken(): String? {
        return sharedPreferences.getString("jwt_token", null)
    }

    fun getWalletId(): String? = sharedPreferences.getString("wallet_id", null)?.takeIf { it.isNotBlank() }

    fun getUserId(): Int? = sharedPreferences.getInt("user_id", -1).takeIf { it > 0 }

    fun getRole(): String? = sharedPreferences.getString("session_role", null)

    fun getTerminalId(): String? = sharedPreferences.getString("terminal_id", null)?.takeIf { it.isNotBlank() }

    fun clearToken() {
        sharedPreferences.edit().remove("jwt_token").remove("wallet_id").remove("user_id")
            .remove("session_role").remove("terminal_id").commit()
    }

    @Synchronized
    fun pendingRequestId(kind: String, walletId: String): String {
        val name = "pending_request:$kind:$walletId"
        sharedPreferences.getString(name, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        check(sharedPreferences.edit().putString(name, id).commit())
        return id
    }

    fun completeRequest(kind: String, walletId: String) {
        check(sharedPreferences.edit().remove("pending_request:$kind:$walletId").commit())
    }
}
