package com.example.prototipopagosoffline.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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
            .remove("sync_access_required_for_token")
            .remove("terminal_id")
            .commit()

    fun saveMerchantSession(token: String, terminalId: String, userId: Int): Boolean =
        sharedPreferences.edit()
            .putString("jwt_token", token)
            .putString("terminal_id", terminalId)
            .putInt("user_id", userId)
            .putString("session_role", "comercio")
            .remove("sync_access_required_for_token")
            .remove("wallet_id")
            .commit()

    fun saveToken(token: String) {
        check(sharedPreferences.edit().putString("jwt_token", token).remove("sync_access_required_for_token").commit()) {
            "No se pudo guardar el token"
        }
    }

    fun getToken(): String? {
        return sharedPreferences.getString("jwt_token", null)
    }

    fun getWalletId(): String? = sharedPreferences.getString("wallet_id", null)?.takeIf { it.isNotBlank() }

    fun getUserId(): Int? = sharedPreferences.getInt("user_id", -1).takeIf { it > 0 }

    fun getRole(): String? = sharedPreferences.getString("session_role", null)

    fun syncPauseReason(): String? = getToken()?.let {
        if (sharedPreferences.getString("sync_access_required_for_token", null) == tokenDigest(it))
            sharedPreferences.getString("sync_pause_reason", null) else null
    }

    private fun tokenDigest(token: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun observeSyncPauseReason() = callbackFlow<String?> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(syncPauseReason())
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(syncPauseReason())
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun markSyncPaused(token: String, reason: String) {
        require(reason in setOf("REAUTH_REQUIRED", "ATTENTION_REQUIRED"))
        if (getToken() == token) check(sharedPreferences.edit()
            .putString("sync_access_required_for_token", tokenDigest(token))
            .putString("sync_pause_reason", reason).commit())
    }

    fun getTerminalId(): String? = sharedPreferences.getString("terminal_id", null)?.takeIf { it.isNotBlank() }

    fun clearToken() {
        sharedPreferences.edit().remove("jwt_token").remove("wallet_id").remove("user_id")
            .remove("session_role").remove("terminal_id").commit()
    }

    @Synchronized
    fun demoIdentity(role: String): com.example.prototipopagosoffline.network.DeviceLoginRequest {
        require(role == "cliente" || role == "comercio")
        val idKey = "demo_installation:$role"
        val secretKey = "demo_device_secret:$role"
        var id = sharedPreferences.getString(idKey, null)
        var secret = sharedPreferences.getString(secretKey, null)
        check((id == null) == (secret == null)) { "Identidad demo incompleta; requiere recuperacion" }
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            secret = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it) }
            check(sharedPreferences.edit().putString(idKey, id).putString(secretKey, secret).commit())
        }
        return com.example.prototipopagosoffline.network.DeviceLoginRequest(id, role, requireNotNull(secret))
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
